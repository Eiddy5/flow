package org.cses.flow.executor;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.cses.flow.infrastructure.jooq.FlowJooqCondition;
import org.cses.flow.queues.annotations.FlowQueueListener;
import org.cses.flow.worker.WorkerResultEvent;

/** 独立结果消费者，接纳和清理事务提交后返回允许 ACK。 */
@Singleton
@Requires(condition = FlowJooqCondition.class)
@Requires(property = "flow.executor.worker-result.enabled", notEquals = "false", defaultValue = "true")
public class WorkerResultConsumer {
    private WorkerExecutionCoordinator coordinator;
    /** 注入唯一负责业务状态的 Executor 协调器。
     * @param coordinator 非空 Executor 状态及技术许可协调器
     */
    public WorkerResultConsumer(WorkerExecutionCoordinator coordinator) { this.coordinator = coordinator; }
    /**
     * 接纳同一原尝试的结果，重复消息在清理后仍可确认。
     * @param event 非空的原结果引用，只读
     * @throws RuntimeException 接纳事务失败时抛出，由队列重投而不 ACK
     */
    @FlowQueueListener(subscription = WorkerResultEvent.QUEUE_NAME)
    public void onResult(WorkerResultEvent event) { coordinator.accept(event); }
}
