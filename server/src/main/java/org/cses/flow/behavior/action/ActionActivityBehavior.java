package org.cses.flow.behavior.action;

import org.cses.flow.behavior.ActivityBehavior;
import org.cses.flow.behavior.ActivityExecuteContext;
import org.cses.flow.behavior.ActivityExecuteResult;

public final class ActionActivityBehavior implements ActivityBehavior {

    @Override
    public ActivityExecuteResult execute(ActivityExecuteContext context) {
        Object executor = context.node().config().get("executor");
        if (!"noop".equals(executor)) {
            throw new IllegalArgumentException("Unsupported ACTION executor: " + executor);
        }
        return ActivityExecuteResult.completed();
    }
}
