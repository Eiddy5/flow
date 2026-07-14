package org.cses.flow.runtime.execution.operation;

import java.util.Objects;
import org.cses.flow.definition.model.Node;
import org.cses.flow.runtime.behavior.ActivityBehavior;
import org.cses.flow.runtime.behavior.NodeExecutionResult;
import org.cses.flow.runtime.context.ActivityContext;
import org.cses.flow.runtime.context.OperationContext;
import org.cses.flow.runtime.context.ResumeTarget;
import org.cses.flow.runtime.execution.FlowOperation;
import org.cses.flow.runtime.model.ActivityState;
import org.cses.flow.runtime.model.ExecutorState;
import org.cses.flow.runtime.model.Signal;
import org.cses.flow.runtime.model.TaskCompletedSignal;
import org.cses.flow.runtime.model.TaskState;

public final class ResumeNodeOperation extends FlowOperation {

    private final ResumeTarget target;
    private final Node node;
    private final Signal signal;

    public ResumeNodeOperation(ResumeTarget target, Node node, Signal signal) {
        this.target = Objects.requireNonNull(target, "target");
        this.node = Objects.requireNonNull(node, "node");
        this.signal = Objects.requireNonNull(signal, "signal");
    }

    @Override
    protected void execute(OperationContext context) {
        if (target.task().state() != TaskState.CREATED
                && target.task().state() != TaskState.CLAIMED) {
            throw new IllegalStateException("Task is not waiting: " + target.task().id());
        }
        if (target.activity().state() != ActivityState.RUNNING
                || target.executor().state() != ExecutorState.WAITING
                || !node.id().equals(target.task().nodeId())) {
            throw new IllegalStateException("Resume target is not in a waiting state");
        }

        ActivityBehavior behavior = context.configuration().behaviorRegistry().get(node.type());
        NodeExecutionResult result = behavior.resume(new ActivityContext(
                context.flowContext().flow(),
                target.process(),
                target.executor(),
                node,
                target.activity()), signal);
        if (!(result instanceof NodeExecutionResult.Completed completed)) {
            throw new IllegalStateException("Resuming a waiting Node must complete it");
        }
        if (!(signal instanceof TaskCompletedSignal taskSignal)) {
            throw new IllegalArgumentException("Unsupported Signal: " + signal.getClass().getName());
        }

        target.task().complete(taskSignal.result(), taskSignal.operatorId(), taskSignal.idempotencyKey());
        target.executor().activate();
        context.runtimeSession().update(target.task());
        context.runtimeSession().update(target.process());
        context.scheduler().plan(new LeaveNodeOperation(
                target.executor(), node, target.activity(), completed.output()));
    }
}
