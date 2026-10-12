package org.cses.flow.executor;

import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.cses.flow.core.repositories.worker.ExecutionMessage;
import org.cses.flow.core.repositories.worker.WorkerRuntimeRepository;
import org.cses.flow.core.utils.TimeUtil;
import org.cses.flow.infrastructure.jooq.FlowDatabase;
import org.cses.flow.infrastructure.jooq.FlowJooqCondition;
import org.cses.flow.infrastructure.queues.pulsar.PulsarQueueListeners;
import org.cses.flow.queues.Queue;
import org.cses.flow.worker.WorkerCommand;
import org.cses.flow.worker.WorkerResultEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.x9.jooq.JOOQ;

/** 补发可靠任务、结果和推进消息；Broker 确认只标记，不删除。 */
@Singleton
@Requires(condition = FlowJooqCondition.class)
@Requires(beans = PulsarQueueListeners.class)
public class ExecutionOutboxPublisher {
    private static Logger logger = LoggerFactory.getLogger(ExecutionOutboxPublisher.class);
    private JOOQ jooq;
    private WorkerRuntimeRepository runtime;
    private Queue<WorkerCommand> commands;
    private Queue<WorkerResultEvent> results;
    private Queue<ExecutorEvent> events;
    private PulsarQueueListeners listeners;
    private volatile boolean stopping;

    /** 注入三个独立发布器，发送期间不持有数据库事务。
     * @param jooq 具名 Flow 数据库访问组件，不取得其关闭权
     * @param runtime 非空技术许可和待消费消息仓储
     * @param commands 非空独立 Worker 命令发布器，等待 Broker 受理
     * @param results 非空独立 Worker 结果发布器，等待 Broker 受理
     * @param events 非空 Executor 推进事件发布器，等待 Broker 受理
     * @param listeners 非空持久订阅就绪状态，用于保护首次发送
     */
    public ExecutionOutboxPublisher(@Named(FlowDatabase.DATA_SOURCE_NAME) JOOQ jooq, WorkerRuntimeRepository runtime,
            Queue<WorkerCommand> commands, Queue<WorkerResultEvent> results, Queue<ExecutorEvent> events,
            PulsarQueueListeners listeners) {
        this.jooq = jooq;
        this.runtime = runtime;
        this.commands = commands;
        this.results = results;
        this.events = events;
        this.listeners = listeners;
    }

    /**
     * 关闭时停止新增发送，未确认内容留给下次启动补发。
     * @param event 当前应用关闭事件，只读取关闭信号
     */
    @io.micronaut.runtime.event.annotation.EventListener
    public void stop(io.micronaut.context.event.ShutdownEvent event) { stopping = true; }

    /** 固定批量发布尚未确认的消息，某类消费者停用时保留其 outbox。 */
    @Scheduled(fixedDelay = "${flow.executor.outbox.poll-interval:250ms}")
    public void poll() {
        if (stopping) return;
        var dsl = jooq.createDSLContext();
        for (var message : runtime.pending(dsl, 100)) {
            if (stopping) return;
            try {
                String name = switch (message.kind()) {
                    case WORKER -> WorkerCommand.QUEUE_NAME;
                    case RESULT -> WorkerResultEvent.QUEUE_NAME;
                    case ADVANCE -> ExecutorEvent.QUEUE_NAME;
                };
                if (!listeners.isReady(name, name)) continue;
                switch (message.kind()) {
                    case WORKER -> commands.emit(message.command());
                    case RESULT -> results.emit(message.resultEvent());
                    case ADVANCE -> events.emit(message.advance());
                }
                runtime.published(dsl, message.companyId(), message.id(), TimeUtil.now());
            } catch (RuntimeException failure) {
                logger.warn("Execution outbox publication failed for {}", message.executionId(), failure);
            }
        }
    }
}
