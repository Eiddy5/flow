package org.cses.flow.runtime.execution.operation;

import java.util.Objects;
import org.cses.flow.definition.model.Edge;
import org.cses.flow.runtime.context.OperationContext;
import org.cses.flow.runtime.execution.FlowOperation;
import org.cses.flow.runtime.model.Executor;
import org.cses.flow.runtime.model.Process;

public final class TraverseEdgeOperation extends FlowOperation {

    private final Executor executor;
    private final Edge edge;

    public TraverseEdgeOperation(Executor executor, Edge edge) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.edge = Objects.requireNonNull(edge, "edge");
    }

    @Override
    protected void execute(OperationContext context) {
        if (!edge.sourceId().equals(executor.currentNodeId())) {
            throw new IllegalStateException("Edge source does not match Executor position: " + edge.id());
        }
        Process process = context.flowContext().process();
        executor.moveTo(edge.target());
        context.runtimeSession().update(process);
        context.scheduler().plan(new EnterNodeOperation(executor, edge.target()));
    }
}
