package org.flow.queue.jdbc;

import org.flow.core.async.AsyncOperationProcessedEvent;
import org.flow.core.executor.command.ExecutionCommand;
import org.flow.core.models.executions.*;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.ExecutionKilled;
import org.flow.core.models.executions.LogEntry;
import org.flow.core.models.executions.MetricEntry;
import org.flow.core.models.executions.statistics.ExecutionStatistic;
import org.flow.core.models.flows.FlowInterface;
import org.flow.core.queues.BroadcastQueueInterface;
import org.flow.core.queues.DispatchQueueInterface;
import org.flow.core.queues.KeyedDispatchQueueInterface;
import org.flow.core.queues.VNodeDispatchQueueInterface;
import org.flow.core.runners.*;
import org.flow.core.runners.MultipleConditionEvent;
import org.flow.core.runners.SubflowExecutionEnd;
import org.flow.core.runners.SubflowExecutionResult;
import org.flow.core.runners.WorkerJobEvent;
import org.flow.core.scheduler.events.SchedulerEvent;
import org.flow.core.scheduler.events.TriggerEvent;
import org.flow.core.server.ClusterEvent;
import org.flow.queue.QueueBean;
import org.flow.queue.QueueFactoryInterface;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Secondary;

@Factory
@JdbcQueueEnabled
public class JdbcQueueFactory implements QueueFactoryInterface<JdbcDependencies> {
    @QueueBean
    @Override
    public DispatchQueueInterface<Execution> executionQueue(JdbcDependencies dependencies) {
        return new JdbcDispatchQueue<>(
            Execution.class, dependencies.queueService(), dependencies.jdbcQueueClient(), dependencies.executorsUtils(), dependencies.metricRegistry(), dependencies.ignoreExecutionService()
        );
    }

    @QueueBean
    @Override
    public DispatchQueueInterface<ExecutionCommand> executionCommandQueue(JdbcDependencies dependencies) {
        return new JdbcDispatchQueue<>(
            ExecutionCommand.class, dependencies.queueService(), dependencies.jdbcQueueClient(), dependencies.executorsUtils(), dependencies.metricRegistry(),
            dependencies.ignoreExecutionService()
        );
    }

    @QueueBean
    @Override
    public DispatchQueueInterface<ExecutionEvent> executionEventQueue(JdbcDependencies dependencies) {
        return new JdbcDispatchQueue<>(
            ExecutionEvent.class, dependencies.queueService(), dependencies.jdbcQueueClient(), dependencies.executorsUtils(), dependencies.metricRegistry(),
            dependencies.ignoreExecutionService()
        );
    }

    @QueueBean
    @Override
    public BroadcastQueueInterface<ExecutionKilled> killQueue(JdbcDependencies dependencies) {
        return new JdbcBroadcastQueue<>(
            ExecutionKilled.class, dependencies.queueService(), dependencies.jdbcQueueClient(), dependencies.executorsUtils(), dependencies.metricRegistry(),
            dependencies.ignoreExecutionService()
        );
    }

    @QueueBean
    @Override
    public DispatchQueueInterface<SubflowExecutionResult> subflowExecutionResultQueue(JdbcDependencies dependencies) {
        return new JdbcDispatchQueue<>(
            SubflowExecutionResult.class, dependencies.queueService(), dependencies.jdbcQueueClient(), dependencies.executorsUtils(), dependencies.metricRegistry(),
            dependencies.ignoreExecutionService()
        );
    }

    @QueueBean
    @Override
    public DispatchQueueInterface<SubflowExecutionEnd> subflowExecutionEndQueue(JdbcDependencies dependencies) {
        return new JdbcDispatchQueue<>(
            SubflowExecutionEnd.class, dependencies.queueService(), dependencies.jdbcQueueClient(), dependencies.executorsUtils(), dependencies.metricRegistry(),
            dependencies.ignoreExecutionService()
        );
    }

    @QueueBean
    @Override
    public DispatchQueueInterface<MultipleConditionEvent> multipleConditionEventQueue(JdbcDependencies dependencies) {
        return new JdbcDispatchQueue<>(
            MultipleConditionEvent.class, dependencies.queueService(), dependencies.jdbcQueueClient(), dependencies.executorsUtils(), dependencies.metricRegistry(),
            dependencies.ignoreExecutionService()
        );
    }

    @QueueBean
    @Override
    public BroadcastQueueInterface<FlowInterface> flowQueue(JdbcDependencies dependencies) {
        return new JdbcBroadcastQueue<>(
            FlowInterface.class, dependencies.queueService(), dependencies.jdbcQueueClient(), dependencies.executorsUtils(), dependencies.metricRegistry(),
            dependencies.ignoreExecutionService()
        );
    }

    @QueueBean
    @Override
    public BroadcastQueueInterface<SchedulerEvent> schedulerEventQueue(JdbcDependencies dependencies) {
        return new JdbcBroadcastQueue<>(
            SchedulerEvent.class, dependencies.queueService(), dependencies.jdbcQueueClient(), dependencies.executorsUtils(), dependencies.metricRegistry(),
            dependencies.ignoreExecutionService()
        );
    }

    @QueueBean
    @Override
    public VNodeDispatchQueueInterface<TriggerEvent> triggerEventQueue(JdbcDependencies dependencies) {
        return new JdbcVNodeDispatchQueue<>(
            TriggerEvent.class, dependencies.queueService(), dependencies.jdbcQueueClient(), dependencies.executorsUtils(), dependencies.metricRegistry(), dependencies.ignoreExecutionService()
        );
    }

    @QueueBean
    @Override
    public DispatchQueueInterface<MetricEntry> metricQueue(JdbcDependencies dependencies) {
        return new JdbcDispatchQueue<>(
            MetricEntry.class, dependencies.queueService(), dependencies.jdbcQueueClient(), dependencies.executorsUtils(), dependencies.metricRegistry(), dependencies.ignoreExecutionService()
        );
    }

    @QueueBean
    @Override
    public DispatchQueueInterface<ExecutionStatistic> executionStatisticQueue(JdbcDependencies dependencies) {
        return new JdbcDispatchQueue<>(
            ExecutionStatistic.class, dependencies.queueService(), dependencies.jdbcQueueClient(), dependencies.executorsUtils(), dependencies.metricRegistry(),
            dependencies.ignoreExecutionService()
        );
    }

    @QueueBean
    @Override
    public BroadcastQueueInterface<FollowExecutionEvent> followExecutionQueue(JdbcDependencies dependencies) {
        return new JdbcBroadcastQueue<>(
            FollowExecutionEvent.class, dependencies.queueService(), dependencies.jdbcQueueClient(), dependencies.executorsUtils(), dependencies.metricRegistry(),
            dependencies.ignoreExecutionService()
        );
    }

    @QueueBean
    @Override
    public BroadcastQueueInterface<AsyncOperationProcessedEvent> asyncOperationProcessedEventQueue(JdbcDependencies dependencies) {
        return new JdbcBroadcastQueue<>(
            AsyncOperationProcessedEvent.class, dependencies.queueService(), dependencies.jdbcQueueClient(), dependencies.executorsUtils(), dependencies.metricRegistry(),
            dependencies.ignoreExecutionService()
        );
    }

    @QueueBean
    @Override
    public DispatchQueueInterface<LogEntry> logEntryQueue(JdbcDependencies dependencies) {
        return new JdbcDispatchQueue<>(
            LogEntry.class, dependencies.queueService(), dependencies.jdbcQueueClient(), dependencies.executorsUtils(), dependencies.metricRegistry(), dependencies.ignoreExecutionService()
        );
    }

    @QueueBean
    @Override
    public KeyedDispatchQueueInterface<WorkerJobEvent> workerJobEventQueue(JdbcDependencies dependencies) {
        return new JdbcKeyedDispatchQueue<>(
            WorkerJobEvent.class, dependencies.queueService(), dependencies.jdbcQueueClient(), dependencies.executorsUtils(), dependencies.metricRegistry(),
            dependencies.ignoreExecutionService()
        );
    }

    @QueueBean
    @Override
    public DispatchQueueInterface<WorkerTaskResult> workerTaskResultQueue(JdbcDependencies dependencies) {
        return new JdbcDispatchQueue<>(
            WorkerTaskResult.class, dependencies.queueService(), dependencies.jdbcQueueClient(), dependencies.executorsUtils(), dependencies.metricRegistry(),
            dependencies.ignoreExecutionService()
        );
    }

    @QueueBean
    @Secondary
    @Override
    public BroadcastQueueInterface<ClusterEvent> clusterEventQueue(JdbcDependencies dependencies) {
        return new JdbcBroadcastQueue<>(
            ClusterEvent.class, dependencies.queueService(), dependencies.jdbcQueueClient(), dependencies.executorsUtils(), dependencies.metricRegistry(),
            dependencies.ignoreExecutionService()
        );
    }

    @QueueBean
    @Override
    public DispatchQueueInterface<LoopExecutionEvent> loopExecutionEventQueue(JdbcDependencies dependencies) {
        return new JdbcDispatchQueue<>(
            LoopExecutionEvent.class, dependencies.queueService(), dependencies.jdbcQueueClient(), dependencies.executorsUtils(), dependencies.metricRegistry(),
            dependencies.ignoreExecutionService()
        );
    }
}
