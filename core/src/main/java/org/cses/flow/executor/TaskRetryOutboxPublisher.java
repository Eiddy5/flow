package org.cses.flow.executor;

import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.core.utils.TimeUtil;
import org.cses.flow.infrastructure.jooq.FlowDatabase;
import org.cses.flow.infrastructure.queues.pulsar.PulsarQueue;
import org.cses.flow.infrastructure.queues.pulsar.PulsarQueueListeners;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.x9.jooq.JOOQ;

/** 发布重试 Outbox；所有计划提前交给 Pulsar，由 Broker 负责到期投递。 */
@Singleton
@Requires(beans = {JOOQ.class, PulsarQueueListeners.class})
@Requires(property = "flow.executor.retry.outbox.enabled", notEquals = "false", defaultValue = "true")
public class TaskRetryOutboxPublisher {
    private static Logger logger = LoggerFactory.getLogger(TaskRetryOutboxPublisher.class);
    private volatile boolean stopping;
    private JOOQ jooq;
    private ExecutionRepository executions;
    private PulsarQueue<TaskRetryEvent> queue;
    private PulsarQueueListeners listeners;

    /**
     * 注入既有数据库、运行仓储与调度发布器。
     * @param jooq 具名 Flow 数据库
     * @param executions 完整运行仓储
     * @param queue 专用 Pulsar 重试延迟队列
     * @param listeners 消费订阅启动状态
     */
    public TaskRetryOutboxPublisher(@Named(FlowDatabase.DATA_SOURCE_NAME) JOOQ jooq,
            ExecutionRepository executions, PulsarQueue<TaskRetryEvent> queue, PulsarQueueListeners listeners) {
        this.jooq = jooq;
        this.executions = executions;
        this.queue = queue;
        this.listeners = listeners;
    }

    /**
     * 应用关闭时停止新增发布，未确认消息由下一次启动接续。
     * @param event 当前应用关闭事件
     */
    @io.micronaut.runtime.event.annotation.EventListener
    public void stop(io.micronaut.context.event.ShutdownEvent event) { stopping = true; }

    /** 发布最多 100 个未确认计划，不检查是否到期；发送失败留待下轮补发。 */
    @Scheduled(fixedDelay = "${flow.executor.retry.outbox.poll-interval:1s}")
    public void poll() {
        if (stopping || !listeners.isReady(TaskRetryEvent.QUEUE_NAME, TaskRetryEvent.QUEUE_NAME)) return;
        var dsl = jooq.createDSLContext();
        for (var retry : executions.pendingRetries(dsl, 100)) {
            if (stopping) return;
            try {
                queue.emitAt(TaskRetryEvent.from(retry.companyId(), retry.executionId(), retry.taskRunId(),
                    retry.attemptNumber(), retry.deliverAt()), retry.deliverAt());
                executions.retryPublished(dsl, retry, TimeUtil.now());
            } catch (RuntimeException failure) {
                logger.warn("Retry publication failed for execution {}", retry.executionId(), failure);
            }
        }
    }
}
