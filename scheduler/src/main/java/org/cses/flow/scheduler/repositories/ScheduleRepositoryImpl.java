/*
 * PostgreSQL lock-and-fetch adapted from db-scheduler v16.11.0
 * Copyright (C) Gustav Karlsson
 * Licensed under the Apache License, Version 2.0. See LICENSE-db-scheduler.
 * Modified for Flow: typed JOOQ, tenant-scoped plans, and transaction-held claims.
 */
package org.cses.flow.scheduler.repositories;

import jakarta.inject.Singleton;
import org.cses.flow.common.Fields;
import org.cses.flow.infrastructure.datapilot.FlowDataSourceEngine;
import org.cses.flow.scheduler.ScheduleData;
import org.cses.flow.infrastructure.repositories.BaseRepository;
import org.cses.flow.scheduler.repositories.entries.ScheduleEntry;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

import java.util.Optional;
import java.util.Set;

import static org.flow.gen.flow.Tables.TRIGGER_SCHEDULES;

/** PostgreSQL 调度持久化；调用方拥有短事务，领取锁仅在该事务内有效。 */
@Singleton
public class ScheduleRepositoryImpl extends BaseRepository implements ScheduleRepository {
    /**
     * 绑定调度计划根表与完整身份，连接与短事务由 Scheduler 拥有。
     * @param dataSourceEngine 非空 Flow Engine，提供 CRUD 与事务行锁入口
     */
    public ScheduleRepositoryImpl(FlowDataSourceEngine dataSourceEngine) {
        super(dataSourceEngine, TRIGGER_SCHEDULES);
    }

    /**
     * 保持原配置同步的事务咨询锁坐标，锁随调用方事务结束释放。
     * @param context 非空且已开启短事务的上下文
     * @param companyId 非空白租户身份
     * @param flowKey 非空流程业务键
     */
    @Override
    public void lockFlow(DSLContext context, String companyId, String flowKey) {
        company(companyId);
        execute(context, dsl -> {
            dsl.select(DSL.field("pg_advisory_xact_lock(hashtextextended({0}, 0))", Object.class,
                    DSL.val(companyId + ":" + flowKey))).fetch();
            return null;
        });
    }

    /**
     * 排他领取一条到期计划，跳过其他节点已持锁的行。
     * @param context 非空、已开启事务的 Flow 数据库上下文
     * @param now 当前 UTC 毫秒时间
     * @return 持锁计划；没有可领取行时为空
     */
    @Override
    public Optional<ScheduleData> lockNext(DSLContext context, long now) {
        var entry = execute(context, dsl -> dsl.selectFrom(TRIGGER_SCHEDULES)
            .where(TRIGGER_SCHEDULES.AVAILABLE_AT.le(now))
            .orderBy(TRIGGER_SCHEDULES.AVAILABLE_AT, TRIGGER_SCHEDULES.COMPANY_ID, TRIGGER_SCHEDULES.ID)
            .limit(1).forUpdate().skipLocked().fetchOneInto(ScheduleEntry.class));
        return Optional.ofNullable(entry).map(ScheduleEntry::to);
    }

    /**
     * 在配置同步事务内读取并锁住已有计划。
     * @param context 已开启事务的数据库上下文
     * @param companyId 非空租户
     * @param triggerId 非空触发器身份
     * @return 当前计划；不存在时为空
     */
    @Override
    public Optional<ScheduleData> find(DSLContext context, String companyId, String triggerId) {
        var entry = execute(context, dsl -> dsl.selectFrom(TRIGGER_SCHEDULES)
            .where(identity(companyId, triggerId)).forUpdate().fetchOneInto(ScheduleEntry.class));
        return Optional.ofNullable(entry).map(ScheduleEntry::to);
    }

    /**
     * 保存新计划；重复配置同步不覆盖已有命中身份。
     * @param context 调用方数据库上下文
     * @param data 非空的新计划
     */
    @Override
    public void insert(DSLContext context, ScheduleData data) {
        identity(data.companyId(), data.triggerId());
        createIfAbsent(context, ScheduleEntry.from(data).buildInsertMap());
    }

    /**
     * 推进已持锁计划或删除已耗尽计划；旧命中不能覆盖后来计划。
     * @param context 持有计划行锁的事务上下文
     * @param current 当前计划身份
     * @param next 下一次计划；null 表示耗尽或取消
     * @throws IllegalStateException 当前计划身份已经失效
     */
    @Override
    public void advance(DSLContext context, ScheduleData current, ScheduleData next) {
        int changed = execute(context, dsl -> {
            return next == null
                ? dsl.deleteFrom(TRIGGER_SCHEDULES).where(identity(current)).execute()
                : updateStatement(dsl, ScheduleEntry.from(next).toMap())
                    .set(TRIGGER_SCHEDULES.LAST_FAILURE_AT, (Long) null)
                    .set(TRIGGER_SCHEDULES.LAST_ERROR, (String) null)
                    .where(identity(current)).execute();
        });
        if (changed != 1) throw new IllegalStateException("Schedule occurrence changed concurrently");
    }

    /**
     * 失败事务回滚后延后原命中，不更换事件和运行身份；已推进或取消的计划不回写。
     * @param context 回滚后可用的数据库上下文
     * @param data 失败的计划坐标
     * @param now 本次失败的 UTC 毫秒时间
     * @param retryAt 下次可领取的 UTC 毫秒时间
     * @param error 非空失败描述
     */
    @Override
    public void failed(DSLContext context, ScheduleData data, long now, long retryAt, String error) {
        execute(context, dsl -> {
            dsl.update(TRIGGER_SCHEDULES).set(TRIGGER_SCHEDULES.AVAILABLE_AT, retryAt)
                .set(TRIGGER_SCHEDULES.ATTEMPTS, TRIGGER_SCHEDULES.ATTEMPTS.add(1))
                .set(TRIGGER_SCHEDULES.LAST_FAILURE_AT, now).set(TRIGGER_SCHEDULES.LAST_ERROR, error)
                .where(identity(data)).execute();
            return null;
        });
    }

    /**
     * 删除该流程中已禁用、移除或随流程删除的计划。
     * @param context 配置同步事务上下文
     * @param companyId 非空租户
     * @param flowKey 非空流程键
     * @param active 当前启用触发器身份；允许为空，读取但不修改
     */
    @Override
    public void removeObsolete(DSLContext context, String companyId, String flowKey, Set<String> active) {
        deleteRows(context, company(companyId)
            .and(TRIGGER_SCHEDULES.FLOW_KEY.eq(flowKey)).and(TRIGGER_SCHEDULES.ID.notIn(active)));
    }

    /**
     * 校验调度计划的租户并构造查询条件。
     * @param companyId 非空白租户身份
     * @return 当前租户的计划条件
     */
    private Condition company(String companyId) {
        if (companyId == null || companyId.isBlank()) {
            throw new IllegalArgumentException(Fields.COMPANY_ID + " must not be blank");
        }
        return TRIGGER_SCHEDULES.COMPANY_ID.eq(companyId);
    }

    /**
     * 校验触发器身份并构造计划查询条件。
     * @param companyId 非空白租户身份
     * @param id 非空白触发器身份
     * @return 当前租户内指定计划的条件
     */
    private Condition identity(String companyId, String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(Fields.ID + " must not be blank");
        }
        return company(companyId).and(TRIGGER_SCHEDULES.ID.eq(id));
    }

    /**
     * 为计划回写生成租户、触发器和当前命中的完整条件。
     * @param data 非空计划坐标
     * @return 不会匹配其他租户或后来命中的条件
     */
    private Condition identity(ScheduleData data) {
        return identity(data.companyId(), data.triggerId()).and(TRIGGER_SCHEDULES.EVENT_ID.eq(data.eventId()));
    }
}
