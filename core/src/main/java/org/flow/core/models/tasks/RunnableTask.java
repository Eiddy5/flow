package org.flow.core.models.tasks;

import org.flow.core.models.Plugin;
import org.flow.core.models.WorkerJobLifecycle;
import org.flow.core.runners.RunContext;

/**
 * Interface for tasks that are run in the Worker.
 */
public interface RunnableTask<T extends Output> extends Plugin, WorkerJobLifecycle {
    /**
     * This method is called inside the Worker to run (execute) the task.
     */
    T run(RunContext runContext) throws Exception;
}
