package org.cses.flow.scheduler;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Named;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowId;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.utils.TimeUtil;
import org.cses.flow.extensions.triggers.FlowTrigger;
import org.cses.flow.infrastructure.jooq.FlowJooqCondition;
import org.cses.flow.infrastructure.repositories.triggers.entries.SchedulerEventEntry;
import org.cses.flow.infrastructure.repositories.triggers.entries.TriggerRunEntry;
import org.cses.flow.queues.Queue;
import org.cses.flow.queues.annotations.FlowQueueListener;
import org.cses.flow.scheduler.events.SchedulerEvent;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.paas.common.util.StringUtil;
import org.x9.jooq.JOOQ;
import java.util.HashSet;
import java.util.Objects;

import static org.flow.gen.flow.Tables.*;

/** 与定时扫描独立的命中持久化、outbox 投递及上游终态分发。 */
@Context
@Requires(condition = FlowJooqCondition.class)
public class TriggerRuntime {
    private DSLContext dsl;
    private Queue<SchedulerEvent> queue;
    private TriggerExecutionPublisher publisher;
    private FlowRepository flows;
    private boolean stopping;

    /**
     * @param jooq 具名 Flow 数据库
     * @param queue 持久事件队列
     * @param publisher Core 启动入口
     * @param flows Flow 版本仓储
     */
    public TriggerRuntime(@Named("flow") JOOQ jooq, Queue<SchedulerEvent> queue,
            TriggerExecutionPublisher publisher, FlowRepository flows) {
        this.dsl = jooq.createDSLContext(); this.queue = queue; this.publisher = publisher; this.flows = flows;
    }

    /**
     * 在同一 SQL 中保存命中和 outbox；并发重复命中返回首次身份。
     * @param context 当前数据库上下文
     * @param event 已校验并绑定输入的命中
     *
     * @return 原始持久化的命中快照，不重新选择版本
     */
    public SchedulerEvent stage(DSLContext context, SchedulerEvent event) {
        var run = TriggerRunEntry.from(event);
        var outbox = SchedulerEventEntry.from(event, TimeUtil.now());
        var inserted = DSL.name("inserted_run").as(context.insertInto(TRIGGER_RUNS).set(run.toMap())
            .onConflictDoNothing().returning(TRIGGER_RUNS.ID));
        context.with(inserted).insertInto(SCHEDULER_EVENTS)
            .columns(SCHEDULER_EVENTS.COMPANY_ID, SCHEDULER_EVENTS.ID, SCHEDULER_EVENTS.KIND,
                SCHEDULER_EVENTS.PAYLOAD, SCHEDULER_EVENTS.CREATED_AT, SCHEDULER_EVENTS.RETRY_AT, SCHEDULER_EVENTS.ATTEMPTS)
            .select(context.select(DSL.val(outbox.companyId), DSL.val(outbox.id), DSL.val(outbox.kind),
                DSL.val(outbox.payload), DSL.val(outbox.createdAt), DSL.val(outbox.retryAt), DSL.val(0)).from(inserted)).execute();
        return Objects.requireNonNull(context.selectFrom(TRIGGER_RUNS)
            .where(TRIGGER_RUNS.COMPANY_ID.eq(event.companyId())).and(TRIGGER_RUNS.FLOW_KEY.eq(event.flowKey()))
            .and(TRIGGER_RUNS.TRIGGER_KEY.eq(event.triggerKey())).and(TRIGGER_RUNS.SOURCE_KEY.eq(event.sourceKey()))
            .fetchOneInto(TriggerRunEntry.class)).to();
    }

    /** 有限批次投递；Broker 受理后标记，失败保持可重试。此任务不依赖定时器启用状态。 */
    @Scheduled(fixedDelay = "1s", initialDelay = "1s")
    public synchronized void publishPending() {
        if (stopping) return;
        var pending = dsl.selectFrom(SCHEDULER_EVENTS).where(SCHEDULER_EVENTS.PUBLISHED_AT.isNull())
            .and(SCHEDULER_EVENTS.RETRY_AT.le(TimeUtil.now())).orderBy(SCHEDULER_EVENTS.CREATED_AT).limit(100)
            .fetchInto(SchedulerEventEntry.class);
        for (var entry : pending) {
            try {
                queue.emit(entry.to());
                dsl.update(SCHEDULER_EVENTS).set(SCHEDULER_EVENTS.PUBLISHED_AT, TimeUtil.now())
                    .set(SCHEDULER_EVENTS.LAST_ERROR, (String) null)
                    .where(SCHEDULER_EVENTS.COMPANY_ID.eq(entry.companyId)).and(SCHEDULER_EVENTS.ID.eq(entry.id)).execute();
            } catch (RuntimeException failure) {
                dsl.update(SCHEDULER_EVENTS).set(SCHEDULER_EVENTS.ATTEMPTS, entry.attempts + 1)
                    .set(SCHEDULER_EVENTS.RETRY_AT, TimeUtil.now() + 1000)
                    .set(SCHEDULER_EVENTS.LAST_ERROR, failure.getClass().getSimpleName())
                    .where(SCHEDULER_EVENTS.COMPANY_ID.eq(entry.companyId)).and(SCHEDULER_EVENTS.ID.eq(entry.id)).execute();
            }
        }
    }

    /**
     * @param event 容器关闭通知；先等待当前投递退出，再允许关闭消息资源
     */
    @io.micronaut.runtime.event.annotation.EventListener
    public synchronized void shutdown(io.micronaut.context.event.ShutdownEvent event) { stopping = true; }

    /**
     * @param event 内部持久事件；配置事件由独立的定时订阅处理，失败抛出以请求重投
     */
    @FlowQueueListener(subscription = "flow-trigger-dispatch")
    public void onEvent(SchedulerEvent event) {
        if ("TRIGGER_FIRED".equals(event.kind())) publisher.send(event);
        else if ("FLOW_TERMINATED".equals(event.kind())) dispatchFlow(event);
    }

    /**
     * 一次性固定终态事件的完整匹配集合，重复投递不匹配后来新增的流程。
     * @param event 与执行终态原子保存的事件
     */
    private void dispatchFlow(SchedulerEvent event) {
        dsl.transaction(config -> {
            var context = DSL.using(config);
            var stored = context.selectFrom(SCHEDULER_EVENTS)
                .where(SCHEDULER_EVENTS.COMPANY_ID.eq(event.companyId())).and(SCHEDULER_EVENTS.ID.eq(event.id()))
                .forUpdate().fetchOneInto(SchedulerEventEntry.class);
            if (stored == null || !stored.to().equals(event)) throw new IllegalArgumentException("Unknown terminal event");
            if (stored.processedAt != null) return;
            var ancestry = ancestors(context, event);
            // ponytail: 初期逐个加载同租户正式 Flow，数量增长后增加按上游 key 查询的触发索引。
            var keys = context.selectDistinct(FLOWS.KEY).from(FLOWS)
                .where(FLOWS.COMPANY_ID.eq(event.companyId())).and(FLOWS.DRAFT.eq(false)).fetch(FLOWS.KEY);
            for (String key : keys) {
                Flow flow = flows.findLatestByFlowId(context, FlowId.from(event.companyId(), key)).orElse(null);
                if (flow == null || flow.deleted() || ancestry.contains(key)) continue;
                for (var definition : flow.triggers()) {
                    if (!(definition instanceof FlowTrigger trigger) || trigger.disabled()
                        || !trigger.flowKey().equals(event.flowKey())
                        || trigger.states().stream().noneMatch(state -> state.name().equals(event.inputs().get("state")))) continue;
                    stage(context, SchedulerEvent.occurrence(flow.companyId(), StringUtil.newId(), flow.key(), flow.version(),
                        trigger.key(), null, StringUtil.newId(), flow.updater().id(), flow.bindInputs(trigger.inputs()),
                        "flow:" + event.executionId()));
                }
            }
            context.update(SCHEDULER_EVENTS).set(SCHEDULER_EVENTS.PROCESSED_AT, TimeUtil.now())
                .where(SCHEDULER_EVENTS.COMPANY_ID.eq(event.companyId())).and(SCHEDULER_EVENTS.ID.eq(event.id())).execute();
        });
    }

    /**
     * 沿持久化触发来源查找已经过的 Flow，阻止同一触发链重复进入祖先流程。
     * @param context 数据库快照
     * @param event 当前上游终态
     * @return 此链路上的 Flow key 集合
     */
    private java.util.Set<String> ancestors(DSLContext context, SchedulerEvent event) {
        var keys = new HashSet<String>(); keys.add(event.flowKey());
        var seen = new HashSet<String>();
        String id = event.executionId();
        while (id != null && seen.add(id)) {
            var run = context.selectFrom(TRIGGER_RUNS).where(TRIGGER_RUNS.COMPANY_ID.eq(event.companyId()))
                .and(TRIGGER_RUNS.EXECUTION_ID.eq(id)).fetchOneInto(TriggerRunEntry.class);
            if (run == null || !run.sourceKey.startsWith("flow:")) break;
            id = run.sourceKey.substring(5);
            String key = context.select(EXECUTIONS.FLOW_KEY).from(EXECUTIONS)
                .where(EXECUTIONS.COMPANY_ID.eq(event.companyId())).and(EXECUTIONS.ID.eq(id)).fetchOne(EXECUTIONS.FLOW_KEY);
            if (key != null) keys.add(key);
        }
        return keys;
    }
}
