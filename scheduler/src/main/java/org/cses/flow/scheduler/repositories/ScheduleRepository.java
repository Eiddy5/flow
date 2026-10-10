/*
 * PostgreSQL lock-and-fetch adapted from db-scheduler v16.11.0
 * Copyright (C) Gustav Karlsson
 * Licensed under the Apache License, Version 2.0. See LICENSE-db-scheduler.
 * Modified for Flow: typed JOOQ, tenant-scoped plans, and transaction-held claims.
 */
package org.cses.flow.scheduler.repositories;

import jakarta.inject.Singleton;
import org.cses.flow.scheduler.ScheduleData;
import org.cses.flow.scheduler.repositories.entries.ScheduleEntry;
import org.jooq.Condition;
import org.jooq.DSLContext;

import java.util.Optional;
import java.util.Set;

import static org.flow.gen.flow.Tables.TRIGGER_SCHEDULES;

/** PostgreSQL 调度持久化；调用方拥有短事务，领取锁仅在该事务内有效。 */
@Singleton
public class ScheduleRepository {
    /**
     * 排他领取一条到期计划，跳过其他节点已持锁的行。
     * @param context 非空、已开启事务的 Flow 数据库上下文
     * @param now 当前 UTC 毫秒时间
     * @return 持锁计划；没有可领取行时为空
     */
    public Optional<ScheduleData> lockNext(DSLContext context, long now) {
        var entry = context.selectFrom(TRIGGER_SCHEDULES)
            .where(TRIGGER_SCHEDULES.AVAILABLE_AT.le(now))
            .orderBy(TRIGGER_SCHEDULES.AVAILABLE_AT, TRIGGER_SCHEDULES.COMPANY_ID, TRIGGER_SCHEDULES.ID)
            .limit(1).forUpdate().skipLocked().fetchOneInto(ScheduleEntry.class);
        return Optional.ofNullable(entry).map(ScheduleEntry::to);
    }

    /**
     * 在配置同步事务内读取并锁住已有计划。
     * @param context 已开启事务的数据库上下文
     * @param companyId 非空租户
     * @param triggerId 非空触发器身份
     * @return 当前计划；不存在时为空
     */
    public Optional<ScheduleData> find(DSLContext context, String companyId, String triggerId) {
        var entry = context.selectFrom(TRIGGER_SCHEDULES)
            .where(TRIGGER_SCHEDULES.COMPANY_ID.eq(companyId)).and(TRIGGER_SCHEDULES.ID.eq(triggerId))
            .forUpdate().fetchOneInto(ScheduleEntry.class);
        return Optional.ofNullable(entry).map(ScheduleEntry::to);
    }

    /**
     * 保存新计划；重复配置同步不覆盖已有命中身份。
     * @param context 调用方数据库上下文
     * @param data 非空的新计划
     */
    public void insert(DSLContext context, ScheduleData data) {
        context.insertInto(TRIGGER_SCHEDULES).set(ScheduleEntry.from(data).buildInsertMap())
            .onConflictDoNothing().execute();
    }

    /**
     * 推进已持锁计划或删除已耗尽计划；旧命中不能覆盖后来计划。
     * @param context 持有计划行锁的事务上下文
     * @param current 当前计划身份
     * @param next 下一次计划；null 表示耗尽或取消
     * @throws IllegalStateException 当前计划身份已经失效
     */
    public void advance(DSLContext context, ScheduleData current, ScheduleData next) {
        int changed = next == null
            ? context.deleteFrom(TRIGGER_SCHEDULES).where(identity(current)).execute()
            : context.update(TRIGGER_SCHEDULES).set(ScheduleEntry.from(next).buildUpdateMap())
                .set(TRIGGER_SCHEDULES.LAST_FAILURE_AT, (Long) null)
                .set(TRIGGER_SCHEDULES.LAST_ERROR, (String) null)
                .where(identity(current)).execute();
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
    public void failed(DSLContext context, ScheduleData data, long now, long retryAt, String error) {
        context.update(TRIGGER_SCHEDULES).set(TRIGGER_SCHEDULES.AVAILABLE_AT, retryAt)
            .set(TRIGGER_SCHEDULES.ATTEMPTS, TRIGGER_SCHEDULES.ATTEMPTS.add(1))
            .set(TRIGGER_SCHEDULES.LAST_FAILURE_AT, now).set(TRIGGER_SCHEDULES.LAST_ERROR, error)
            .where(identity(data)).execute();
    }

    /**
     * 删除该流程中已禁用、移除或随流程删除的计划。
     * @param context 配置同步事务上下文
     * @param companyId 非空租户
     * @param flowKey 非空流程键
     * @param active 当前启用触发器身份；允许为空，读取但不修改
     */
    public void removeObsolete(DSLContext context, String companyId, String flowKey, Set<String> active) {
        context.deleteFrom(TRIGGER_SCHEDULES).where(TRIGGER_SCHEDULES.COMPANY_ID.eq(companyId))
            .and(TRIGGER_SCHEDULES.FLOW_KEY.eq(flowKey)).and(TRIGGER_SCHEDULES.ID.notIn(active)).execute();
    }

    /**
     * 为计划回写生成租户、触发器和当前命中的完整条件。
     * @param data 非空计划坐标
     * @return 不会匹配其他租户或后来命中的条件
     */
    private Condition identity(ScheduleData data) {
        return TRIGGER_SCHEDULES.COMPANY_ID.eq(data.companyId())
            .and(TRIGGER_SCHEDULES.ID.eq(data.triggerId())).and(TRIGGER_SCHEDULES.EVENT_ID.eq(data.eventId()));
    }
}
