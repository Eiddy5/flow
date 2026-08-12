package org.cses.flow.executor;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.worker.WorkerDispatcher;
import org.cses.flow.worker.WorkerTask;
import org.cses.flow.worker.WorkerTaskResult;
import org.jooq.DSLContext;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Commits one Execution's state-machine changes and Worker effects.
 *
 * <p>This is an internal runtime collaborator. Queue lifecycle and command
 * routing belong to {@link DefaultExecutor}; Executor command interpretation
 * belongs to
 * {@link org.cses.flow.executor.handlers.ExecutorCommandHandler}.</p>
 */
@Singleton
public final class ExecutionRunner {

    private final ExecutionRepository executionRepository;
    private final ExecutorService executorService;
    private final WorkerDispatcher workerDispatcher;

    @Inject
    public ExecutionRunner(
        ExecutionRepository executionRepository,
        ExecutorService executorService,
        WorkerDispatcher workerDispatcher
    ) {
        this.executionRepository = executionRepository;
        this.executorService = executorService;
        this.workerDispatcher = workerDispatcher;
    }

    public <S extends Session<U>, U extends User> Execution execute(
        S session,
        DSLContext dsl,
        ExecutorContext context
    ) {
        requireRuntime(session, dsl, context);
        return drive(session, dsl, context, true);
    }

    public <S extends Session<U>, U extends User> Execution resume(
        S session,
        DSLContext dsl,
        ExecutorContext context,
        String taskRunId,
        Map<String, ?> outputs
    ) {
        requireRuntime(session, dsl, context);
        executorService.resume(context, taskRunId, outputs);
        return drive(session, dsl, context, false);
    }

    public <S extends Session<U>, U extends User> Execution cancel(
        S session,
        DSLContext dsl,
        ExecutorContext context
    ) {
        requireRuntime(session, dsl, context);
        executorService.kill(context);
        return drive(session, dsl, context, false);
    }

    private <S extends Session<U>, U extends User> Execution drive(
        S session,
        DSLContext dsl,
        ExecutorContext context,
        boolean captureUnexpectedTaskFailure
    ) {
        while (true) {
            executorService.process(context);
            List<WorkerTask> workerTasks = context.takeWorkerTasks();
            boolean executionUpdated = context.takeExecutionUpdated();
            if (executionUpdated) {
                persist(dsl, context);
            }

            if (workerTasks.isEmpty()) {
                if (executionUpdated && context.canBeProcessed()) {
                    continue;
                }
                return context.execution().copy();
            }

            for (WorkerTask workerTask : workerTasks) {
                executorService.dispatch(context, workerTask);
                if (context.takeExecutionUpdated()) {
                    persist(dsl, context);
                }

                WorkerTaskResult result = dispatchWorkerTask(
                    session,
                    dsl,
                    workerTask,
                    captureUnexpectedTaskFailure
                );
                executorService.applyResult(context, result);
                if (result.targetState() == State.Type.FAILED
                    || result.targetState() == State.Type.KILLED) {
                    if (context.takeExecutionUpdated()) {
                        persist(dsl, context);
                    }
                    return context.execution().copy();
                }
            }
        }
    }

    private <S extends Session<U>, U extends User>
    WorkerTaskResult dispatchWorkerTask(
        S session,
        DSLContext dsl,
        WorkerTask workerTask,
        boolean captureUnexpectedTaskFailure
    ) {
        if (!captureUnexpectedTaskFailure) {
            return workerDispatcher.dispatch(session, dsl, workerTask);
        }
        try {
            return workerDispatcher.dispatch(session, dsl, workerTask);
        } catch (RuntimeException exception) {
            return WorkerTaskResult.failed(
                workerTask,
                unexpectedFailure(exception)
            );
        }
    }

    private static String unexpectedFailure(RuntimeException exception) {
        String detail = exception.getMessage();
        String type = exception.getClass().getSimpleName();
        if (detail == null || detail.isBlank()) {
            return "RunnableTask failed unexpectedly: " + type;
        }
        return "RunnableTask failed unexpectedly: " + type + ": " + detail;
    }

    private void persist(DSLContext dsl, ExecutorContext context) {
        executionRepository.save(dsl, context.execution());
    }

    private static void requireRuntime(
        Object session,
        DSLContext dsl,
        ExecutorContext context
    ) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(dsl, "dsl");
        Objects.requireNonNull(context, "context");
    }
}
