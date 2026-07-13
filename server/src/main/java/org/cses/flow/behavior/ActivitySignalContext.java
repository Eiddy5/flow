package org.cses.flow.behavior;

import java.util.Objects;
import org.cses.flow.definition.model.Node;
import org.cses.flow.runtime.context.FlowContext;
import org.cses.flow.runtime.model.Activity;
import org.cses.flow.runtime.model.Executor;
import org.cses.flow.runtime.model.TaskCompletedSignal;
import org.cses.flow.task.model.Task;

public record ActivitySignalContext(
        FlowContext flowContext,
        Executor executor,
        Activity activity,
        Task task,
        Node node,
        TaskCompletedSignal signal) {

    public ActivitySignalContext {
        Objects.requireNonNull(flowContext, "flowContext");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(signal, "signal");
    }
}
