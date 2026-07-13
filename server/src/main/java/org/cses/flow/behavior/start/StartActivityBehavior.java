package org.cses.flow.behavior.start;

import org.cses.flow.behavior.ActivityBehavior;
import org.cses.flow.behavior.ActivityExecuteContext;
import org.cses.flow.behavior.ActivityExecuteResult;

public final class StartActivityBehavior implements ActivityBehavior {

    @Override
    public ActivityExecuteResult execute(ActivityExecuteContext context) {
        return ActivityExecuteResult.completed();
    }
}
