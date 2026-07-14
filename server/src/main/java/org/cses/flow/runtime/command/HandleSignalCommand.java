package org.cses.flow.runtime.command;

import java.util.Objects;
import org.cses.flow.definition.model.Flow;
import org.cses.flow.definition.model.Node;
import org.cses.flow.runtime.context.CommandContext;
import org.cses.flow.runtime.context.FlowContext;
import org.cses.flow.runtime.context.ResumeTarget;
import org.cses.flow.runtime.execution.operation.ResumeNodeOperation;
import org.cses.flow.runtime.model.Process;
import org.cses.flow.runtime.model.TaskCompletedSignal;

public final class HandleSignalCommand implements Command<Process> {

    private final TaskCompletedSignal signal;

    public HandleSignalCommand(TaskCompletedSignal signal) {
        this.signal = Objects.requireNonNull(signal, "signal");
    }

    @Override
    public Process execute(CommandContext context) {
        ResumeTarget target = context.runtimeSession().loadResumeTarget(signal.taskId());
        if (target.task().completedWith(signal.idempotencyKey())) {
            return target.process();
        }
        Flow flow = context.definitionSession().loadBoundFlow(target.process().flowId());
        Node node = flow.node(target.task().nodeId());
        context.bindFlowContext(new FlowContext(flow, target.process(), target));
        context.executionQueue().plan(new ResumeNodeOperation(target, node, signal));
        return target.process();
    }
}
