package org.cses.flow.worker;

import jakarta.inject.Singleton;
import org.cses.flow.core.runner.RunContext;
import org.cses.flow.core.domains.tasks.RunResult;

import java.util.Objects;

/**
 * Generic Worker boundary that directly invokes a RunnableTask.
 */
@Singleton
public class WorkerDispatcher {

    public WorkerTaskResult dispatch(WorkerTask workerTask) {
        Objects.requireNonNull(workerTask, "workerTask");
        RunContext context = RunContext.builder()
            .variables(workerTask.variables())
            .build();
        RunResult result = Objects.requireNonNull(
            workerTask.runnableTask().run(context),
            "RunnableTask result"
        );
        return WorkerTaskResult.from(workerTask, result);
    }
}
