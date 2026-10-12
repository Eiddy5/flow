package org.cses.flow.worker;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.cses.flow.core.repositories.worker.ExecutionMessage;
import org.cses.flow.core.repositories.worker.WorkerDelivery;
import org.cses.flow.core.repositories.worker.WorkerRuntimeRepository;
import org.cses.flow.core.utils.TimeUtil;
import org.cses.flow.executor.WorkerExecutionCoordinator;
import org.cses.flow.infrastructure.jooq.FlowDatabase;
import org.cses.flow.infrastructure.queues.pulsar.PulsarQueueListeners;
import org.cses.flow.infrastructure.jooq.FlowJooqCondition;
import org.cses.flow.queues.Queue;
import org.cses.flow.queues.annotations.FlowQueueListener;
import org.paas.common.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.x9.jooq.JOOQ;
import java.util.concurrent.ConcurrentHashMap;

/** 独立任务消费者：领取确认后运行，结果可靠交接完成后才允许任务 ACK。 */
@Singleton
@Requires(condition = FlowJooqCondition.class)
@Requires(beans = PulsarQueueListeners.class)
@Requires(property = "flow.worker.enabled", notEquals = "false", defaultValue = "true")
public class WorkerCommandConsumer {
    private static Logger logger = LoggerFactory.getLogger(WorkerCommandConsumer.class);
    private WorkerExecutionCoordinator coordinator;
    private WorkerDispatcher dispatcher;
    private WorkerRuntimeRepository runtime;
    private Queue<WorkerResultEvent> results;
    private JOOQ jooq;
    private long leaseMillis;
    private PulsarQueueListeners listeners;
    private ConcurrentHashMap<String, WorkerDelivery> active = new ConcurrentHashMap<>();

    /**
     * 注入执行协调、独立结果发布及订阅就绪状态；调用期间不持有数据库事务。
     * @param coordinator 非空 Executor 状态与许可协调器
     * @param dispatcher 非空 RunnableTask 单次调用器
     * @param runtime 非空许可与待消费消息仓储
     * @param results 非空结果发布器，等待 Broker 受理
     * @param jooq 非空具名 Flow 数据库访问，不关闭其共享资源
     * @param leaseMillis 租约毫秒数，至少 3000ms，需大于每秒续租间隔
     * @param listeners 非空订阅状态，结果订阅未就绪时不允许任务 ACK
     * @throws IllegalArgumentException 租约小于 3000ms 时抛出
     */
    public WorkerCommandConsumer(WorkerExecutionCoordinator coordinator, WorkerDispatcher dispatcher,
            WorkerRuntimeRepository runtime, Queue<WorkerResultEvent> results,
            @Named(FlowDatabase.DATA_SOURCE_NAME) JOOQ jooq,
            @Value("${flow.worker.lease-millis:10000}") long leaseMillis, PulsarQueueListeners listeners) {
        if (leaseMillis < 3000) throw new IllegalArgumentException("Worker lease must be at least 3000ms");
        this.coordinator = coordinator;
        this.dispatcher = dispatcher;
        this.runtime = runtime;
        this.results = results;
        this.jooq = jooq;
        this.leaseMillis = leaseMillis;
        this.listeners = listeners;
    }

    /** 消费原命令；数据库或 Broker 失败继续抛出，由队列 NACK 重投。
     * @param command 非空的原任务命令，读取稳定身份
     * @throws RuntimeException 领取、结果保存或 Broker 受理失败时抛出，保留消息并由队列重投
     */
    @FlowQueueListener(subscription = WorkerCommand.QUEUE_NAME, concurrency = 4)
    public void onCommand(WorkerCommand command) {
        var start = coordinator.start(command, StringUtil.newId(), leaseMillis);
        ExecutionMessage result = start.result();
        if (start.task() != null) {
            String owner = start.delivery().ownerId();
            active.put(owner, start.delivery());
            try {
                WorkerTaskResult output;
                try {
                    output = dispatcher.dispatch(start.task());
                } catch (RuntimeException taskFailure) {
                    String detail = taskFailure.getMessage();
                    output = WorkerTaskResult.failed(start.task(), detail == null || detail.isBlank()
                        ? taskFailure.getClass().getSimpleName() : detail);
                }
                result = coordinator.reported(start.delivery(), output);
            } finally {
                active.remove(owner);
            }
        }
        if (result != null) {
            if (!listeners.isReady(WorkerResultEvent.QUEUE_NAME, WorkerResultEvent.QUEUE_NAME)) {
                throw new IllegalStateException("Worker result subscription is not ready");
            }
            results.emit(result.resultEvent());
            runtime.published(jooq.createDSLContext(), result.companyId(), result.id(), TimeUtil.now());
        }
        runtime.consumed(jooq.createDSLContext(), command.companyId(), command.id());
    }

    /** 调用期间仅续租当前技术许可；失去许可的输出会被协调器拒绝。 */
    @Scheduled(fixedDelay = "1s")
    public void renew() {
        for (var delivery : active.values()) {
            try {
                long now = TimeUtil.now();
                if (!runtime.renew(jooq.createDSLContext(), delivery, now, Math.addExact(now, leaseMillis))) {
                    logger.warn("Worker permit expired for execution {}", delivery.executionId());
                }
            } catch (RuntimeException failure) {
                logger.warn("Worker lease renewal failed for execution {}", delivery.executionId(), failure);
            }
        }
    }
}
