package org.cses.flow.runtime.context;

import java.util.Objects;
import org.cses.flow.runtime.model.Activity;
import org.cses.flow.runtime.model.Executor;
import org.cses.flow.runtime.model.Process;
import org.cses.flow.runtime.model.Task;

public record ResumeTarget(Process process, Executor executor, Activity activity, Task task) {

    public ResumeTarget {
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(task, "task");
    }
}
