package org.cses.flow.behavior.wait;

import java.util.Map;
import org.cses.flow.behavior.ActivityBehavior;
import org.cses.flow.behavior.ActivityExecuteContext;
import org.cses.flow.behavior.ActivityExecuteResult;
import org.cses.flow.behavior.ActivitySignalContext;
import org.cses.flow.behavior.ActivitySignalResult;
import org.cses.flow.behavior.TaskDefinition;

public final class WaitActivityBehavior implements ActivityBehavior {

    @Override
    public ActivityExecuteResult execute(ActivityExecuteContext context) {
        Object completionValue = context.node().config().get("completion");
        if (!(completionValue instanceof Map<?, ?> completion)
                || !"manual".equals(completion.get("mode"))) {
            throw new IllegalArgumentException(
                    "WAIT Node requires config.completion.mode=manual: " + context.node().id());
        }
        String taskName = String.valueOf(
                context.node().config().getOrDefault("taskName", context.node().name()));
        return ActivityExecuteResult.waiting(new TaskDefinition(taskName));
    }

    @Override
    public ActivitySignalResult handleSignal(ActivitySignalContext context) {
        return ActivitySignalResult.completed(context.signal().payload());
    }
}
