package org.cses.flow.runtime.behavior.impl;

import java.util.Map;
import org.cses.flow.runtime.behavior.ActivityBehavior;
import org.cses.flow.runtime.behavior.NodeExecutionResult;
import org.cses.flow.runtime.behavior.TaskDefinition;
import org.cses.flow.runtime.context.ActivityContext;
import org.cses.flow.runtime.model.Signal;
import org.cses.flow.runtime.model.TaskCompletedSignal;

public final class WaitActivityBehavior implements ActivityBehavior {

    @Override
    public NodeExecutionResult execute(ActivityContext context) {
        Object completion = context.node().config().get("completion");
        if (!(completion instanceof Map<?, ?> values)
                || !"manual".equals(values.get("mode"))) {
            throw new IllegalArgumentException(
                    "WAIT Node requires completion.mode=manual: " + context.node().id());
        }
        Object configuredName = context.node().config().get("taskName");
        String taskName = configuredName instanceof String value ? value : context.node().name();
        return new NodeExecutionResult.Waiting(new TaskDefinition(taskName));
    }

    @Override
    public NodeExecutionResult resume(ActivityContext context, Signal signal) {
        if (!(signal instanceof TaskCompletedSignal completedSignal)) {
            throw new IllegalArgumentException("WAIT Node requires TaskCompletedSignal");
        }
        return new NodeExecutionResult.Completed(completedSignal.result());
    }
}
