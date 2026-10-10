package org.cses.flow.scheduler;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Named;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowId;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.repositories.triggers.TriggerRepository;
import org.cses.flow.core.utils.TimeUtil;
import org.cses.flow.extensions.triggers.FlowTrigger;
import org.cses.flow.infrastructure.jooq.FlowJooqCondition;
import org.cses.flow.infrastructure.jooq.FlowDatabase;
import org.cses.flow.queues.Queue;
import org.cses.flow.queues.annotations.FlowQueueListener;
import org.cses.flow.scheduler.events.SchedulerEvent;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.paas.common.util.StringUtil;
import org.x9.jooq.JOOQ;

/** 与定时扫描独立的命中持久化、outbox 投递及上游终态分发。 */
@Context
@Requires(condition = FlowJooqCondition.class)
public class TriggerRuntime {
    private DSLContext dsl;
    private Queue<SchedulerEvent> queue;
    private TriggerExecutionPublisher publisher;
    private FlowRepository flows;
    private TriggerRepository triggers;
    private boolean stopping;

    /**
     * 装配触发编排入口，持久化操作由触发仓储使用当前上下文完成。
     * @param jooq 具名 Flow 数据库
     * @param queue 持久事件队列
     * @param publisher Core 启动入口
     * @param flows Flow 版本仓储
     * @param triggers 命中与内部事件仓储
     */
    public TriggerRuntime(@Named(FlowDatabase.DATA_SOURCE_NAME) JOOQ jooq, Queue<SchedulerEvent> queue,
            TriggerExecutionPublisher publisher, FlowRepository flows, TriggerRepository triggers) {
        this.dsl = jooq.createDSLContext();
        this.queue = queue;
        this.publisher = publisher;
        this.flows = flows;
        this.triggers = triggers;
    }

    /**
     * 在同一 SQL 中保存命中和 outbox；并发重复命中返回首次身份。
     * @param context 当前数据库上下文
     * @param event 已校验并绑定输入的命中
     *
     * @return 原始持久化的命中快照，不重新选择版本
     */
    public SchedulerEvent stage(DSLContext context, SchedulerEvent event) {
        return triggers.stage(context, event, TimeUtil.now());
    }

    /** 有限批次投递；Broker 受理后标记，失败保持可重试。此任务不依赖定时器启用状态。 */
    @Scheduled(fixedDelay = "1s", initialDelay = "1s")
    public synchronized void publishPending() {
        if (stopping) return;
        var pending = triggers.pending(dsl, TimeUtil.now(), 100);
        for (var entry : pending) {
            try {
                queue.emit(entry.event());
                triggers.published(dsl, entry.event(), TimeUtil.now());
            } catch (RuntimeException failure) {
                triggers.failed(dsl, entry, TimeUtil.now() + 1000, failure.getClass().getSimpleName());
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
            var stored = triggers.lock(context, event.companyId(), event.id()).orElse(null);
            if (stored == null || !stored.event().equals(event)) throw new IllegalArgumentException("Unknown terminal event");
            if (stored.processedAt() != null) return;
            var ancestry = triggers.ancestors(context, event);
            // ponytail: 初期逐个加载同租户正式 Flow，数量增长后增加按上游 key 查询的触发索引。
            var keys = triggers.flowKeys(context, event.companyId());
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
            triggers.processed(context, event, TimeUtil.now());
        });
    }
}
