package org.cses.flow.runtime.execution;

import java.util.Objects;
import org.cses.flow.definition.model.Edge;
import org.cses.flow.definition.model.Node;
import org.cses.flow.runtime.context.FlowContext;
import org.cses.flow.runtime.model.Executor;
import org.cses.flow.runtime.model.Process;

final class TakeOutgoingEdgesOperation implements ExecutionOperation {

    private final String executorId;
    private final ExecutionOperationFactory operationFactory;

    TakeOutgoingEdgesOperation(String executorId, ExecutionOperationFactory operationFactory) {
        this.executorId = Objects.requireNonNull(executorId, "executorId");
        this.operationFactory = Objects.requireNonNull(operationFactory, "operationFactory");
    }

    @Override
    public void execute(FlowContext flowContext) {
        Process process = flowContext.process();
        Executor executor = process.executor(executorId);
        Node source = flowContext.flow().node(executor.currentNodeId());
        if (source.outgoing().size() != 1) {
            throw new IllegalStateException(
                    "Node must have exactly one outgoing Edge in VER-FLOW-001: " + source.id());
        }
        Edge edge = source.outgoing().getFirst();
        executor.moveTo(edge.target());
        operationFactory.processRepository().save(process);
        flowContext.executionQueue().plan(operationFactory.continueExecutor(executor.id()));
    }
}
