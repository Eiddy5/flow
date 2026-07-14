package org.cses.flow.runtime.command;

import java.util.Map;
import java.util.Objects;
import org.cses.flow.definition.model.Flow;
import org.cses.flow.runtime.context.CommandContext;
import org.cses.flow.runtime.context.FlowContext;
import org.cses.flow.runtime.execution.operation.EnterNodeOperation;
import org.cses.flow.runtime.model.Executor;
import org.cses.flow.runtime.model.Process;

public final class StartFlowCommand implements Command<Process> {

    private final String flowId;

    public StartFlowCommand(String flowId) {
        this.flowId = Objects.requireNonNull(flowId, "flowId");
    }

    @Override
    public Process execute(CommandContext context) {
        Flow flow = context.definitionSession().resolveStartFlow(flowId);
        Process process = new Process(
                context.configuration().idGenerator().nextId("process"),
                flow,
                Map.of());
        Executor executor = process.createRootExecutor(
                context.configuration().idGenerator().nextId("executor"),
                flow.startNode());
        context.runtimeSession().insert(process);
        context.bindFlowContext(new FlowContext(flow, process));
        context.executionQueue().plan(new EnterNodeOperation(executor, flow.startNode()));
        return process;
    }
}
