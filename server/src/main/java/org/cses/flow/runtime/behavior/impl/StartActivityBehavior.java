package org.cses.flow.runtime.behavior.impl;

import java.util.Map;
import org.cses.flow.runtime.behavior.ActivityBehavior;
import org.cses.flow.runtime.behavior.NodeExecutionResult;
import org.cses.flow.runtime.context.ActivityContext;

public final class StartActivityBehavior implements ActivityBehavior {
    @Override
    public NodeExecutionResult execute(ActivityContext context) {
        return new NodeExecutionResult.Completed(Map.of());
    }
}
