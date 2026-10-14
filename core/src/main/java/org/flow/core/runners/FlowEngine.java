package org.flow.core.runners;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.flow.core.server.Service;
import org.flow.core.utils.Await;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

/** 本项目的原生运行入口；使用同一应用容器，不隐式建表或取得连接池所有权。 */
@Singleton
@Requires(property = "flow.server-type", value = "STANDALONE")
public class FlowEngine implements AutoCloseable {
    private Provider<Executor> executor;
    private Provider<Worker> worker;
    private Provider<Scheduler> scheduler;
    private Provider<Indexer> indexer;
    private Provider<SystemWorker> systemWorker;
    private Duration startTimeout;
    private int workerThreads;
    private List<Service> services = new ArrayList<>();
    private List<Service> stopOrder = new ArrayList<>();
    private ExecutorService starters;
    private AtomicReference<Throwable> failure = new AtomicReference<>();
    private volatile boolean running;
    private volatile boolean started;
    private boolean closed;

    /**
     * 保存调用应用容器中的上游服务提供者；构造不启动执行或调度。
     * @param executor 原生执行器
     * @param worker 原生 Worker
     * @param scheduler 原生调度器
     * @param indexer 原生索引器
     * @param systemWorker 原生系统任务 Worker
     * @param startTimeout 最大服务就绪等待时间
     * @param workerThreads Worker 并发数，必须为正数
     * @throws IllegalArgumentException 并发数或超时非正数时抛出
     */
    public FlowEngine(Provider<Executor> executor,
        Provider<Worker> worker, Provider<Scheduler> scheduler, Provider<Indexer> indexer,
        Provider<SystemWorker> systemWorker,
        @Property(name = "flow.engine.start-timeout", defaultValue = "PT1M") Duration startTimeout,
        @Property(name = "flow.engine.worker-threads", defaultValue = "4") int workerThreads) {
        if (workerThreads < 1 || startTimeout.isNegative() || startTimeout.isZero()) {
            throw new IllegalArgumentException("引擎并发数和启动超时必须为正数");
        }
        this.executor = executor;
        this.worker = worker;
        this.scheduler = scheduler;
        this.indexer = indexer;
        this.systemWorker = systemWorker;
        this.startTimeout = startTimeout;
        this.workerThreads = workerThreads;
    }

    /**
     * 启动五类原生服务并等待就绪；启动失败会停止已经取得的服务。
     * @throws IllegalStateException 已关闭、曾启动失败、服务未就绪或上游启动失败时抛出
     */
    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException("已关闭的原生服务不能在同一 Context 重启；请由调用应用重建 Context");
        }
        if (running) {
            return;
        }
        if (started) {
            throw new IllegalStateException("引擎已尝试启动", failure.get());
        }
        started = true;
        starters = Executors.newFixedThreadPool(5, Thread.ofPlatform().name("flow-start-", 0).factory());
        try {
            Executor e = register(executor.get());
            Worker w = register(worker.get());
            Scheduler s = register(scheduler.get());
            Indexer i = register(indexer.get());
            SystemWorker sw = register(systemWorker.get());
            // 先停止新触发和执行中的 Worker，再停止处理结果的 Executor、Indexer。
            stopOrder = List.of(s, w, sw, e, i);
            launch(e);
            launch(() -> w.start(workerThreads));
            launch(s);
            launch(i);
            launch(sw::start);
            Await.await().atMost(startTimeout).until(() -> failure.get() != null || services.stream().allMatch(this::ready));
            if (failure.get() != null) {
                throw new IllegalStateException("原生引擎启动失败", failure.get());
            }
            running = true;
        } catch (RuntimeException exception) {
            failure.compareAndSet(null, exception);
            close();
            throw exception;
        }
    }

    /**
     * 记录已创建服务，确保后续装配失败时仍可回收。
     * @param service 调用应用提供的原生服务
     * @param <T> 服务类型
     * @return 同一服务对象
     */
    private <T extends Service> T register(T service) {
        services.add(service);
        return service;
    }

    /**
     * 将启动异常传回调用方，避免在启动线程中静默丢失。
     * @param action 原生服务的启动动作
     */
    private void launch(Runnable action) {
        starters.execute(() -> {
            try {
                action.run();
            } catch (Throwable exception) {
                failure.compareAndSet(null, exception);
            }
        });
    }

    /**
     * 查询上游服务是否已就绪。
     * @param service 原生服务
     * @return 服务处于运行或维护状态时为 true
     */
    private boolean ready(Service service) {
        return service.getState() == Service.ServiceState.RUNNING || service.getState() == Service.ServiceState.MAINTENANCE;
    }

    /**
     * 查询当前引擎是否可接受调用应用提交。
     * @return 启动成功且没有故障停止请求时为 true
     */
    public boolean isRunning() {
        return running && failure.get() == null && services.stream().allMatch(this::ready);
    }

    /**
     * 查询本实例是否尝试过启动；启动失败或停止后仍为 true，调用方应重建 Context 再启动。
     * @return 已调用 start 时为 true，尚未调用时为 false
     */
    public boolean hasStarted() {
        return started;
    }

    /** 请求故障停止，另起线程避免服务在自身回调中等待自己退出。 */
    public void requestStop() {
        if (failure.compareAndSet(null, new IllegalStateException("Flow 原生服务请求停止"))) {
            Thread.ofVirtual().name("flow-stop").start(this::close);
        }
    }

    /** 关闭引擎服务；幂等执行，不关闭调用应用容器、数据源或其它业务 Bean。 */
    @PreDestroy
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        running = false;
        var closing = stopOrder.isEmpty() ? services.reversed() : stopOrder;
        for (Service service : closing) {
            try {
                service.close();
            } catch (RuntimeException exception) {
                failure.compareAndSet(null, exception);
            }
        }
        if (starters != null) {
            starters.shutdownNow();
        }
    }
}
