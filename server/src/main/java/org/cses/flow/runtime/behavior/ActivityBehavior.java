package org.cses.flow.runtime.behavior;

import org.cses.flow.runtime.context.ActivityContext;
import org.cses.flow.runtime.model.Signal;

public interface ActivityBehavior {

    NodeExecutionResult execute(ActivityContext context);

    default NodeExecutionResult resume(ActivityContext context, Signal signal) {
        throw new IllegalStateException(
                "Node does not accept external signals: " + context.node().id());
    }
}
