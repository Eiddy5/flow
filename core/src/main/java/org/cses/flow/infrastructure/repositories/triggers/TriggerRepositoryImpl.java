package org.cses.flow.infrastructure.repositories.triggers;

import jakarta.inject.Singleton;
import org.cses.flow.common.Fields;
import org.cses.flow.infrastructure.datapilot.FlowDataSourceEngine;
import org.cses.flow.infrastructure.repositories.flows.entries.FlowEntry;
import org.cses.flow.infrastructure.repositories.executions.entries.ExecutionEntry;
import org.cses.flow.core.repositories.triggers.TriggerRepository;
import org.cses.flow.infrastructure.repositories.BaseRepository;
import org.cses.flow.infrastructure.repositories.triggers.entries.SchedulerEventEntry;
import org.cses.flow.infrastructure.repositories.triggers.entries.TriggerRunEntry;
import org.cses.flow.scheduler.events.SchedulerEvent;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.Set;

import static org.flow.gen.flow.Tables.EXECUTIONS;
import static org.flow.gen.flow.Tables.FLOWS;
import static org.flow.gen.flow.Tables.SCHEDULER_EVENTS;
import static org.flow.gen.flow.Tables.TRIGGER_RUNS;

/** 持久化触发命中及内部事件；运行时只负责匹配、队列发布和事务编排。 */
@Singleton
public class TriggerRepositoryImpl extends BaseRepository implements TriggerRepository {
    /**
     * 绑定命中根表与完整身份，不创建连接或隐式事务。
     * @param dataSourceEngine 非空 Flow Engine，提供 CRUD 与事务锁入口
     */
    public TriggerRepositoryImpl(FlowDataSourceEngine dataSourceEngine) {
        super(dataSourceEngine, TRIGGER_RUNS);
    }

    /**
     * 保持既有 PostgreSQL 事务咨询锁的来源坐标，锁随调用方事务结束释放。
     * @param context 非空且已开启事务的上下文
     * @param companyId 非空白租户身份
     * @param flowKey 非空流程键
     * @param triggerKey 非空触发器键
     * @param sourceKey 非空且已校验的来源键
     */
    @Override
    public void lockSource(DSLContext context, String companyId, String flowKey, String triggerKey, String sourceKey) {
        company(companyId);
        execute(context, dsl -> {
            dsl.select(DSL.field("pg_advisory_xact_lock(hashtextextended({0}, 0))", Object.class,
                    DSL.val(companyId + ":" + flowKey + ":" + triggerKey + ":" + sourceKey))).fetch();
            return null;
        });
    }

    /**
     * 按原幂等来源恢复完整命中，通过 Entry 转换为不可变事件。
     * @param context 非空数据库上下文
     * @param companyId 非空白租户身份
     * @param flowKey 非空流程键
     * @param triggerKey 非空触发器键
     * @param sourceKey 非空且已校验的来源键
     * @return 独立命中快照；不存在时为空
     */
    @Override
    public Optional<SchedulerEvent> findBySource(DSLContext context, String companyId,
            String flowKey, String triggerKey, String sourceKey) {
        return Optional.ofNullable(readOne(query(context, company(companyId)
                .and(TRIGGER_RUNS.FLOW_KEY.eq(flowKey)).and(TRIGGER_RUNS.TRIGGER_KEY.eq(triggerKey))
                .and(TRIGGER_RUNS.SOURCE_KEY.eq(sourceKey))), TriggerRunEntry.class))
                .map(TriggerRunEntry::to);
    }

    /**
     * 用单 SQL 保存命中与 outbox，重复命中返回首次快照。
     * @param context 非空调用方数据库上下文
     * @param event 非空且已完成输入绑定的命中事件，不修改输入
     * @param now 当前 UTC 毫秒时间
     * @return 数据库中的首次命中快照
     */
    @Override
    public SchedulerEvent stage(DSLContext context, SchedulerEvent event, long now) {
        identity(event.companyId(), event.id());
        var run = TriggerRunEntry.from(event);
        var outbox = SchedulerEventEntry.from(event, now);
        execute(context, dsl -> {
            var inserted = DSL.name("inserted_run").as(insertStatement(dsl, run.toMap())
                    .onConflictDoNothing().returning(TRIGGER_RUNS.ID));
            dsl.with(inserted).insertInto(SCHEDULER_EVENTS)
                    .columns(SCHEDULER_EVENTS.COMPANY_ID, SCHEDULER_EVENTS.ID, SCHEDULER_EVENTS.KIND,
                            SCHEDULER_EVENTS.PAYLOAD, SCHEDULER_EVENTS.CREATED_AT,
                            SCHEDULER_EVENTS.RETRY_AT, SCHEDULER_EVENTS.ATTEMPTS)
                    .select(dsl.select(DSL.val(outbox.companyId), DSL.val(outbox.id), DSL.val(outbox.kind),
                            DSL.val(outbox.payload), DSL.val(outbox.createdAt), DSL.val(outbox.retryAt),
                            DSL.val(0)).from(inserted)).execute();
            return null;
        });
        return findBySource(context, event.companyId(), event.flowKey(), event.triggerKey(), event.sourceKey())
                .orElseThrow(() -> new IllegalStateException("Trigger occurrence was not persisted"));
    }

    /**
     * 读取指定租户的命中及受理时间。
     * @param context 非空数据库上下文
     * @param companyId 非空白租户身份
     * @param id 非空白命中身份
     * @return 独立命中回执；不存在时为空
     */
    @Override
    public Optional<Occurrence> find(DSLContext context, String companyId, String id) {
        return Optional.ofNullable(readOne(query(context, identity(companyId, id)), TriggerRunEntry.class))
                .map(entry -> Occurrence.from(entry.to(), entry.acceptedAt));
    }

    /**
     * 记录同一命中的队列受理时间。
     * @param context 非空数据库上下文
     * @param event 非空已核验命中
     * @param now 当前 UTC 毫秒时间
     */
    @Override
    public void accepted(DSLContext context, SchedulerEvent event, long now) {
        updateFields(context, TRIGGER_RUNS, identity(event.companyId(), event.id()),
                Map.of(TRIGGER_RUNS.ACCEPTED_AT.getName(), now));
    }

    /**
     * 有限批次扫描已经到重试时间的未发布内部事件。
     * @param context 非空数据库上下文
     * @param now 当前 UTC 毫秒时间
     * @param limit 正数批量上限
     * @return 按创建时间排列的事件集合，没有时为空
     * @throws IllegalArgumentException 批量上限不足一时抛出
     */
    @Override
    public List<StoredEvent> pending(DSLContext context, long now, int limit) {
        if (limit < 1) throw new IllegalArgumentException("Invalid event outbox limit");
        return query(context, SCHEDULER_EVENTS, SCHEDULER_EVENTS.PUBLISHED_AT.isNull()
                .and(SCHEDULER_EVENTS.RETRY_AT.le(now))).asc(SCHEDULER_EVENTS.CREATED_AT).pageSize(limit)
                .list(SchedulerEventEntry.class).items.stream()
                .map(entry -> StoredEvent.from(entry.to(), entry.processedAt, entry.attempts)).toList();
    }

    /**
     * Broker 确认后标记发布成功并清除上次错误。
     * @param context 非空数据库上下文
     * @param event 非空已发布事件
     * @param now 当前 UTC 毫秒时间
     */
    @Override
    public void published(DSLContext context, SchedulerEvent event, long now) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(SCHEDULER_EVENTS.PUBLISHED_AT.getName(), now);
        fields.put(SCHEDULER_EVENTS.LAST_ERROR.getName(), null);
        updateFields(context, SCHEDULER_EVENTS, eventIdentity(event.companyId(), event.id()), fields);
    }

    /**
     * 按完整身份记录投递失败，不改变事件内容或命中身份。
     * @param context 非空数据库上下文
     * @param stored 非空读取时事件快照
     * @param retryAt 下次可发布 UTC 毫秒时间
     * @param error 非空失败描述
     */
    @Override
    public void failed(DSLContext context, StoredEvent stored, long retryAt, String error) {
        updateFields(context, SCHEDULER_EVENTS, eventIdentity(stored.event().companyId(), stored.event().id()),
                Map.of(SCHEDULER_EVENTS.ATTEMPTS.getName(), stored.attempts() + 1,
                    SCHEDULER_EVENTS.RETRY_AT.getName(), retryAt, SCHEDULER_EVENTS.LAST_ERROR.getName(), error));
    }

    /**
     * 读取并锁定同一租户的内部事件，锁由调用方短事务持有。
     * @param context 非空事务上下文
     * @param companyId 非空白租户身份
     * @param id 非空白事件身份
     * @return 事件快照与处理元数据；不存在时为空
     */
    @Override
    public Optional<StoredEvent> lock(DSLContext context, String companyId, String id) {
        return Optional.ofNullable(execute(context, dsl -> dsl.selectFrom(SCHEDULER_EVENTS)
                .where(eventIdentity(companyId, id)).forUpdate().fetchOneInto(SchedulerEventEntry.class)))
                .map(entry -> StoredEvent.from(entry.to(), entry.processedAt, entry.attempts));
    }

    /**
     * 当前匹配集合已经持久化后标记原事件处理完成。
     * @param context 非空且持有原事件行锁的事务上下文
     * @param event 非空原事件
     * @param now 当前 UTC 毫秒时间
     */
    @Override
    public void processed(DSLContext context, SchedulerEvent event, long now) {
        updateFields(context, SCHEDULER_EVENTS, eventIdentity(event.companyId(), event.id()),
                Map.of(SCHEDULER_EVENTS.PROCESSED_AT.getName(), now));
    }

    /**
     * 按持久化来源遍历同一租户的触发链，不跨租户读取祖先。
     * @param context 非空数据库快照上下文
     * @param event 非空上游终态事件
     * @return 独立祖先流程键集合，包含当前上游流程
     */
    @Override
    public Set<String> ancestors(DSLContext context, SchedulerEvent event) {
        var keys = new HashSet<String>();
        keys.add(event.flowKey());
        var seen = new HashSet<String>();
        String executionId = event.executionId();
        while (executionId != null && seen.add(executionId)) {
            var run = readOne(query(context, company(event.companyId()).and(TRIGGER_RUNS.EXECUTION_ID.eq(executionId))),
                    TriggerRunEntry.class);
            if (run == null || !run.sourceKey.startsWith("flow:")) break;
            executionId = run.sourceKey.substring(5);
            ExecutionEntry execution = readOne(query(context, EXECUTIONS, EXECUTIONS.COMPANY_ID.eq(event.companyId())
                    .and(EXECUTIONS.ID.eq(executionId))), ExecutionEntry.class);
            if (execution != null) keys.add(execution.flowKey);
        }
        return Set.copyOf(keys);
    }

    /**
     * 读取同租户具有正式版本的候选流程键，不替代运行时的最新版本及触发条件核验。
     * @param context 非空数据库上下文
     * @param companyId 非空白租户身份
     * @return 去重的流程键集合，没有时为空
     */
    @Override
    public List<String> flowKeys(DSLContext context, String companyId) {
        company(companyId);
        return query(context, FLOWS, FLOWS.COMPANY_ID.eq(companyId).and(FLOWS.DRAFT.eq(false)))
                .list(FlowEntry.class).items.stream().map(entry -> entry.key).distinct().toList();
    }

    /**
     * 校验触发命中的租户并构造查询条件。
     * @param companyId 非空白租户身份
     * @return 当前租户的触发命中条件
     */
    private Condition company(String companyId) {
        if (companyId == null || companyId.isBlank()) {
            throw new IllegalArgumentException(Fields.COMPANY_ID + " must not be blank");
        }
        return TRIGGER_RUNS.COMPANY_ID.eq(companyId);
    }

    /**
     * 校验触发命中身份并构造完整查询条件。
     * @param companyId 非空白租户身份
     * @param id 非空白命中身份
     * @return 当前租户内指定命中的条件
     */
    private Condition identity(String companyId, String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(Fields.ID + " must not be blank");
        }
        return company(companyId).and(TRIGGER_RUNS.ID.eq(id));
    }

    /**
     * 校验完整事件身份并构造内部事件表条件。
     * @param companyId 非空白租户身份
     * @param id 非空白事件身份
     * @return 同时匹配租户与事件身份的条件
     * @throws IllegalArgumentException 任一身份为空或空白时抛出
     */
    private Condition eventIdentity(String companyId, String id) {
        identity(companyId, id);
        return SCHEDULER_EVENTS.COMPANY_ID.eq(companyId).and(SCHEDULER_EVENTS.ID.eq(id));
    }
}
