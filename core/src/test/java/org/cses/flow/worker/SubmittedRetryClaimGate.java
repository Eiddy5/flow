package org.cses.flow.worker;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.cses.flow.core.repositories.worker.WorkerRuntimeRepository;
import org.cses.flow.executor.WorkerExecutionCoordinator;
import org.cses.flow.infrastructure.jooq.FlowDatabase;
import org.cses.flow.queues.Queue;
import org.cses.flow.queues.annotations.FlowQueueListener;
import org.x9.jooq.JOOQ;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** 只暂停真实命令的领取确认；放行后完整调用生产消费入口，不替换状态或执行结果。 */
@Singleton
@Requires(property = "flow.test.retry-claim-gate", value = "true")
public class SubmittedRetryClaimGate {
    private WorkerCommandConsumer delegate;
    private ConcurrentHashMap<Integer, CountDownLatch> arrived = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Integer, Semaphore> releases = new ConcurrentHashMap<>();

    /**
     * 传递当前应用的真实消费协作者并创建场景独占领取控制点。
     * @param coordinator 真实领取和结果协调器
     * @param dispatcher 真实 Runner 调用器
     * @param runtime 真实持久化端口
     * @param results 真实结果队列
     * @param jooq 具名 Flow 数据库
     * @param leaseMillis 生产执行许可租约，毫秒
     * @param listeners 真实队列订阅就绪注册表
     */
    public SubmittedRetryClaimGate(WorkerExecutionCoordinator coordinator, WorkerDispatcher dispatcher,
            WorkerRuntimeRepository runtime, Queue<WorkerResultEvent> results,
            @Named(FlowDatabase.DATA_SOURCE_NAME) JOOQ jooq,
            @Value("${flow.worker.lease-millis:10000}") long leaseMillis,
            org.cses.flow.infrastructure.queues.pulsar.PulsarQueueListeners listeners) {
        delegate = new WorkerCommandConsumer(coordinator, dispatcher, runtime, results, jooq, leaseMillis, listeners);
    }

    /**
     * 暂停第二次及以后的真实任务命令，放行后执行生产确认、Runner、结果保存与 ACK 顺序。
     * @param command Broker 实际投递的原命令
     * @throws IllegalStateException 等待被中断或 30 秒未放行时抛出，让 Broker 重投
     */
    @FlowQueueListener(subscription = WorkerCommand.QUEUE_NAME, concurrency = 4)
    public void onCommand(WorkerCommand command) {
        if (command.attemptNumber() > 1) {
            var permit = releases.computeIfAbsent(command.attemptNumber(), ignored -> new Semaphore(0));
            arrived.computeIfAbsent(command.attemptNumber(), ignored -> new CountDownLatch(1)).countDown();
            try {
                if (!permit.tryAcquire(30, TimeUnit.SECONDS)) throw new IllegalStateException("未放行下一次真实领取");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        delegate.onCommand(command);
    }

    /** 复用生产续租动作，等待领取控制不改变执行中的许可生命周期。 */
    @io.micronaut.scheduling.annotation.Scheduled(fixedDelay = "1s")
    public void renew() {
        delegate.renew();
    }

    /**
     * 等待指定业务尝试的命令真正到达消费层。
     * @param number 大于 1 的下一次业务尝试编号
     * @return 20 秒内收到原命令时为 true
     * @throws InterruptedException 当前测试线程被中断时抛出
     */
    public boolean awaitSubmitted(int number) throws InterruptedException {
        return arrived.computeIfAbsent(number, ignored -> new CountDownLatch(1)).await(20, TimeUnit.SECONDS);
    }

    /**
     * 放行指定尝试的原命令及其可能的重复传输，不修改运行对象。
     * @param number 大于 1 的业务尝试编号
     */
    public void release(int number) {
        releases.computeIfAbsent(number, ignored -> new Semaphore(0)).release(16);
    }
}
