package org.cses.flow.worker;

import jakarta.inject.Singleton;
import org.cses.flow.core.runner.RunContext;
import org.cses.flow.core.domains.tasks.RunResult;
import org.jooq.DSLContext;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Objects;

/**
 * Generic Worker boundary that directly invokes a RunnableTask.
 */
@Singleton
public final class WorkerDispatcher {

    public <S extends Session<U>, U extends User> WorkerTaskResult dispatch(
        S session,
        DSLContext dsl,
        WorkerTask workerTask
    ) {
        Objects.requireNonNull(workerTask, "workerTask");
        RunContext context = RunContext.create(
            session,
            dsl,
            workerTask.inputs()
        );
        RunResult result = Objects.requireNonNull(
            workerTask.runnableTask().run(context),
            "RunnableTask result"
        );
        return WorkerTaskResult.from(workerTask, result);
    }
}
