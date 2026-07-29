package org.cses.flow.extensions.workers;

import jakarta.inject.Singleton;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.extensions.tasks.AutomaticTask;
import org.cses.flow.worker.WorkerContext;
import org.cses.flow.worker.WorkerTaskHandler;
import org.cses.flow.worker.WorkerTaskResult;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Map;

/**
 * First-phase synchronous handler for ordinary automatic Tasks.
 */
@Singleton
public final class DefaultTaskHandler implements WorkerTaskHandler {

    @Override
    public boolean supports(Task task) {
        return task instanceof AutomaticTask;
    }

    @Override
    public <S extends Session<U>, U extends User> WorkerTaskResult execute(
        WorkerContext<S, U> context
    ) {
        return WorkerTaskResult.completed(
            context.workerTask(),
            Map.of()
        );
    }
}
