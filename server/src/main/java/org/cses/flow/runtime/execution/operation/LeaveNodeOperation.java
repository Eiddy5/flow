package org.cses.flow.runtime.execution.operation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.cses.flow.definition.model.Edge;
import org.cses.flow.definition.model.Node;
import org.cses.flow.definition.model.NodeType;
import org.cses.flow.runtime.context.OperationContext;
import org.cses.flow.runtime.execution.FlowOperation;
import org.cses.flow.runtime.model.Activity;
import org.cses.flow.runtime.model.Executor;

public final class LeaveNodeOperation extends FlowOperation {

    private final Executor executor;
    private final Node node;
    private final Activity activity;
    private final Map<String, Object> output;

    public LeaveNodeOperation(
            Executor executor,
            Node node,
            Activity activity,
            Map<String, Object> output) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.node = Objects.requireNonNull(node, "node");
        this.activity = Objects.requireNonNull(activity, "activity");
        this.output = Map.copyOf(Objects.requireNonNull(output, "output"));
    }

    @Override
    protected void execute(OperationContext context) {
        activity.complete(output);
        context.runtimeSession().update(activity);

        List<Edge> outgoing = node.outgoing();
        if (outgoing.isEmpty()) {
            if (node.type() != NodeType.END) {
                throw new IllegalStateException(
                        "Non-END Node must have one outgoing Edge: " + node.id());
            }
            context.scheduler().plan(new EndProcessOperation(executor));
            return;
        }
        if (node.type() == NodeType.END || outgoing.size() != 1) {
            throw new IllegalStateException(
                    "First phase Node must have exactly one outgoing Edge: " + node.id());
        }
        context.scheduler().plan(new TraverseEdgeOperation(executor, outgoing.getFirst()));
    }
}
