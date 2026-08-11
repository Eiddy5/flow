package org.cses.flow.worker;

import jakarta.inject.Singleton;
import org.cses.flow.core.runner.RunContext;
import org.cses.flow.core.domains.tasks.RunResult;
import org.jooq.DSLContext;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.LinkedHashMap;
import java.util.Map;
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
        Map<String, Object> variables = new LinkedHashMap<>(
            workerTask.variables()
        );
        variables.put(
            RunContext.TASK_RUN_ID_VARIABLE,
            workerTask.taskRunId()
        );
        workerTask.parentTaskRunId().ifPresent(parentTaskRunId ->
            variables.put(
                RunContext.PARENT_TASK_RUN_ID_VARIABLE,
                parentTaskRunId
            )
        );
        RunContext context = RunContext.create(
            session,
            dsl,
            variables
        );
        RunResult result = Objects.requireNonNull(
            workerTask.runnableTask().run(context),
            "RunnableTask result"
        );
        return WorkerTaskResult.from(workerTask, result);
    }
}
