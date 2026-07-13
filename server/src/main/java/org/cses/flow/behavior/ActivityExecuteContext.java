package org.cses.flow.behavior;

import java.util.Objects;
import org.cses.flow.definition.model.Node;
import org.cses.flow.runtime.context.FlowContext;
import org.cses.flow.runtime.model.Activity;
import org.cses.flow.runtime.model.Executor;

public record ActivityExecuteContext(
        FlowContext flowContext,
        Executor executor,
        Activity activity,
        Node node) {

    public ActivityExecuteContext {
        Objects.requireNonNull(flowContext, "flowContext");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(node, "node");
    }
}
