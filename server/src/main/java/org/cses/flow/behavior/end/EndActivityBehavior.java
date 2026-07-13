package org.cses.flow.behavior.end;

import org.cses.flow.behavior.ActivityBehavior;
import org.cses.flow.behavior.ActivityExecuteContext;
import org.cses.flow.behavior.ActivityExecuteResult;

public final class EndActivityBehavior implements ActivityBehavior {

    @Override
    public ActivityExecuteResult execute(ActivityExecuteContext context) {
        return ActivityExecuteResult.completed();
    }
}
