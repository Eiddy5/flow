package org.cses.flow.scheduler;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;

import jakarta.inject.Singleton;

import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.executor.ExecutorContext;
import org.cses.flow.executor.ExecutorEvent;
import org.cses.flow.executor.ExecutorService;
import org.cses.flow.executor.handlers.SubFlowExecutionHandler;
import org.cses.flow.queues.Queue;
import org.jooq.DSLContext;

import java.nio.file.*;

/** 只在子终态已保存而父结果尚未传回的位置门控，释放后完整调用原实现。 */
@Singleton
@Replaces(SubFlowExecutionHandler.class)
@Requires(property = "flow.test.native-subflow-gate", value = "true")
public class NativeSubFlowGate extends SubFlowExecutionHandler {
    /**
     * 保留真实仓储、状态机和消息发布器。
     *
     * @param flows 真实定义仓储
     * @param executions 真实运行仓储
     * @param executor 生产状态机
     * @param events 真实 Pulsar 事件发布器
     */
    public NativeSubFlowGate(
            FlowRepository flows,
            ExecutionRepository executions,
            ExecutorService executor,
            Queue<ExecutorEvent> events) {
        super(flows, executions, executor, events);
    }

    /**
     * 准备阶段在精确交接窗口等待强杀，恢复阶段使用原始处理。
     *
     * @param dsl 生产调用链提供的数据库上下文
     * @param child 已持久化的子运行上下文，只用于观察门控
     * @throws IllegalStateException 门控证据保存或等待失败时抛出
     */
    @Override
    public void complete(DSLContext dsl, ExecutorContext child) {
        if (child.execution().isTerminal()
                && child.execution().parentTaskRunId() != null
                && "prepare".equals(System.getProperty("flow.native.mode"))) {
            try {
                Files.writeString(
                        Path.of(System.getProperty("flow.native.directory"), "subflow-gate"),
                        child.execution().id());
                new java.util.concurrent.CountDownLatch(1).await();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
        super.complete(dsl, child);
    }
}
