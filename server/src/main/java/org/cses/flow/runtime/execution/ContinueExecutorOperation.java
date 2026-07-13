package org.cses.flow.runtime.execution;

import java.util.Objects;
import org.cses.flow.behavior.ActivityBehavior;
import org.cses.flow.behavior.ActivityExecuteContext;
import org.cses.flow.behavior.ActivityExecuteResult;
import org.cses.flow.behavior.ActivityExecutionStatus;
import org.cses.flow.definition.model.Node;
import org.cses.flow.definition.model.NodeType;
import org.cses.flow.runtime.context.FlowContext;
import org.cses.flow.runtime.model.Activity;
import org.cses.flow.runtime.model.Executor;
import org.cses.flow.runtime.model.Process;
import org.cses.flow.task.model.Task;

final class ContinueExecutorOperation implements ExecutionOperation {

    private final String executorId;
    private final ExecutionOperationFactory operationFactory;

    ContinueExecutorOperation(String executorId, ExecutionOperationFactory operationFactory) {
        this.executorId = Objects.requireNonNull(executorId, "executorId");
        this.operationFactory = Objects.requireNonNull(operationFactory, "operationFactory");
    }

    @Override
    public void execute(FlowContext flowContext) {
        Process process = flowContext.process();
        Executor executor = process.executor(executorId);
        Node node = flowContext.flow().node(executor.currentNodeId());

        Activity activity = new Activity(
                operationFactory.idGenerator().nextId("activity"),
                process.id(),
                executor.id(),
                node);
        operationFactory.activityRepository().save(activity);

        ActivityBehavior behavior = operationFactory.behaviorRegistry().get(node.type());
        ActivityExecuteResult result = behavior.execute(
                new ActivityExecuteContext(flowContext, executor, activity, node));

        if (result.status() == ActivityExecutionStatus.WAITING) {
            if (result.taskDefinition() == null) {
                throw new IllegalStateException("Waiting Activity requires a Task definition");
            }
            Task task = new Task(
                    operationFactory.idGenerator().nextId("task"),
                    process,
                    executor,
                    activity,
                    node,
                    result.taskDefinition());
            operationFactory.taskRepository().save(task);
            executor.waitForExternalInput();
            operationFactory.processRepository().save(process);
            return;
        }

        activity.complete(result.outputVariables());
        operationFactory.activityRepository().save(activity);

        if (node.type() == NodeType.END) {
            executor.complete();
            process.complete();
            operationFactory.processRepository().save(process);
            return;
        }

        flowContext.executionQueue().plan(operationFactory.takeOutgoingEdges(executor.id()));
    }
}
