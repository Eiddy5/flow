package org.flow.queue;

import org.flow.core.async.AsyncOperationProcessedEvent;
import org.flow.core.executor.command.ExecutionCommand;
import org.flow.core.models.executions.*;
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

public interface QueueFactoryInterface<D> {
    DispatchQueueInterface<Execution> executionQueue(D dependencies);

    DispatchQueueInterface<ExecutionCommand> executionCommandQueue(D dependencies);

    DispatchQueueInterface<ExecutionEvent> executionEventQueue(D dependencies);

    BroadcastQueueInterface<ExecutionKilled> killQueue(D dependencies);

    DispatchQueueInterface<SubflowExecutionResult> subflowExecutionResultQueue(D dependencies);

    DispatchQueueInterface<SubflowExecutionEnd> subflowExecutionEndQueue(D dependencies);

    DispatchQueueInterface<MultipleConditionEvent> multipleConditionEventQueue(D dependencies);

    BroadcastQueueInterface<FlowInterface> flowQueue(D dependencies);

    BroadcastQueueInterface<SchedulerEvent> schedulerEventQueue(D dependencies);

    VNodeDispatchQueueInterface<TriggerEvent> triggerEventQueue(D dependencies);



    BroadcastQueueInterface<FollowExecutionEvent> followExecutionQueue(D dependencies);

    BroadcastQueueInterface<AsyncOperationProcessedEvent> asyncOperationProcessedEventQueue(D dependencies);

    DispatchQueueInterface<LogEntry> logEntryQueue(D dependencies);

    KeyedDispatchQueueInterface<WorkerJobEvent> workerJobEventQueue(D dependencies);

    DispatchQueueInterface<WorkerTaskResult> workerTaskResultQueue(D dependencies);

    BroadcastQueueInterface<ClusterEvent> clusterEventQueue(D dependencies);

    DispatchQueueInterface<LoopExecutionEvent> loopExecutionEventQueue(D dependencies);
}
