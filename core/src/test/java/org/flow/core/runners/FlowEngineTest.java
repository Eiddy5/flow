package org.flow.core.runners;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.flow.core.server.Service;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 原生运行入口的启动失败回收与停止顺序，不依赖具体执行模块。 */
class FlowEngineTest {
    /** 五类原生角色均启动；重复调用不重新启动，停止按消费依赖顺序且仅执行一次。 */
    @Test
    void startsNativeRolesAndStopsThemInDependencyOrder() throws Exception {
        Executor executor = mock(Executor.class);
        Worker worker = mock(Worker.class);
        Scheduler scheduler = mock(Scheduler.class);
        Indexer indexer = mock(Indexer.class);
        SystemWorker systemWorker = mock(SystemWorker.class);
        AtomicReference<Service.ServiceState> executorState = track(executor);
        AtomicReference<Service.ServiceState> workerState = track(worker);
        AtomicReference<Service.ServiceState> schedulerState = track(scheduler);
        AtomicReference<Service.ServiceState> indexerState = track(indexer);
        AtomicReference<Service.ServiceState> systemWorkerState = track(systemWorker);
        doAnswer(call -> { executorState.set(Service.ServiceState.RUNNING); return null; }).when(executor).run();
        doAnswer(call -> { workerState.set(Service.ServiceState.RUNNING); return null; }).when(worker).start(2);
        doAnswer(call -> { schedulerState.set(Service.ServiceState.RUNNING); return null; }).when(scheduler).run();
        doAnswer(call -> { indexerState.set(Service.ServiceState.RUNNING); return null; }).when(indexer).run();
        doAnswer(call -> { systemWorkerState.set(Service.ServiceState.RUNNING); return null; }).when(systemWorker).start();

        try (FlowEngine engine = new FlowEngine(() -> executor, () -> worker, () -> scheduler,
                () -> indexer, () -> systemWorker, Duration.ofSeconds(5), 2)) {
            assertFalse(engine.isRunning());
            engine.start();
            engine.start();
            assertTrue(engine.isRunning());
            verify(executor).run();
            verify(worker).start(2);
            verify(scheduler).run();
            verify(indexer).run();
            verify(systemWorker).start();

            engine.close();
            engine.close();
            assertFalse(engine.isRunning());
            assertThrows(IllegalStateException.class, engine::start);
            var order = inOrder(scheduler, worker, systemWorker, executor, indexer);
            order.verify(scheduler).close();
            order.verify(worker).close();
            order.verify(systemWorker).close();
            order.verify(executor).close();
            order.verify(indexer).close();
        }
        for (Service role : java.util.List.of(executor, worker, scheduler, indexer, systemWorker)) {
            verify(role, times(1)).close();
        }
    }

    /** 装配后续角色失败时回收已经取得的角色，保留原始错误，拒绝复用损坏的实例。 */
    @Test
    void closesPreviouslyResolvedRolesWhenInitializationFails() {
        Executor executor = mock(Executor.class);
        IllegalStateException original = new IllegalStateException("worker initialization failed");
        FlowEngine engine = new FlowEngine(() -> executor, () -> { throw original; },
            () -> mock(Scheduler.class), () -> mock(Indexer.class), () -> mock(SystemWorker.class),
            Duration.ofSeconds(5), 2);

        assertSame(original, assertThrows(IllegalStateException.class, engine::start));
        assertFalse(engine.isRunning());
        verify(executor).close();
        verify(executor, never()).run();
        assertThrows(IllegalStateException.class, engine::start);
    }

    /**
     * 为模拟角色维护可观察状态，close 将其转换为正常终止。
     * @param role 当前模拟角色，不能为 null
     * @return 可由启动动作更新的状态引用
     */
    private AtomicReference<Service.ServiceState> track(Service role) {
        AtomicReference<Service.ServiceState> state = new AtomicReference<>(Service.ServiceState.CREATED);
        when(role.getState()).thenAnswer(call -> state.get());
        doAnswer(call -> { state.set(Service.ServiceState.TERMINATED_GRACEFULLY); return null; }).when(role).close();
        return state;
    }
}
