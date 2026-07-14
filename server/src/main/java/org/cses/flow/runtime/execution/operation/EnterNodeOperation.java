package org.cses.flow.runtime.execution.operation;

import java.util.Objects;
import org.cses.flow.definition.model.Node;
import org.cses.flow.runtime.behavior.ActivityBehavior;
import org.cses.flow.runtime.behavior.NodeExecutionResult;
import org.cses.flow.runtime.context.ActivityContext;
import org.cses.flow.runtime.context.OperationContext;
import org.cses.flow.runtime.execution.FlowOperation;
import org.cses.flow.runtime.model.Activity;
import org.cses.flow.runtime.model.Executor;
import org.cses.flow.runtime.model.ExecutorState;
import org.cses.flow.runtime.model.Process;
import org.cses.flow.runtime.model.Task;

public final class EnterNodeOperation extends FlowOperation {

    private final Executor executor;
    private final Node node;

    public EnterNodeOperation(Executor executor, Node node) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.node = Objects.requireNonNull(node, "node");
    }

    @Override
    protected void execute(OperationContext context) {
        if (executor.state() != ExecutorState.ACTIVE
                || !executor.currentNodeId().equals(node.id())) {
            throw new IllegalStateException("Executor cannot enter Node: " + node.id());
        }
        Process process = context.flowContext().process();
        Activity activity = new Activity(
                context.configuration().idGenerator().nextId("activity"),
                process.id(), executor.id(), node);
        context.runtimeSession().insert(activity);

        ActivityBehavior behavior = context.configuration().behaviorRegistry().get(node.type());
        NodeExecutionResult result = behavior.execute(new ActivityContext(
                context.flowContext().flow(), process, executor, node, activity));
        if (result instanceof NodeExecutionResult.Completed completed) {
            context.scheduler().plan(new LeaveNodeOperation(
                    executor, node, activity, completed.output()));
            return;
        }

        NodeExecutionResult.Waiting waiting = (NodeExecutionResult.Waiting) result;
        Task task = new Task(
                context.configuration().idGenerator().nextId("task"),
                process.id(), executor.id(), activity.id(), node.id(), waiting.task().name());
        executor.waitForExternalInput();
        context.runtimeSession().insert(task);
        context.runtimeSession().update(process);
    }
}
