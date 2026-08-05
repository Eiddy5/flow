package org.cses.flow.executor;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.externaltasks.ExternalTask;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.OrchestrationTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.core.repositories.externaltasks.ExternalTaskRepository;
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
 * Commits one executor cycle's aggregate changes and dispatches its effects.
 *
 * <p>The current implementation uses the command transaction and a
 * synchronous WorkerDispatcher. Persist-before-dispatch keeps Task-owned
 * foreign keys valid while leaving a future durable message/outbox adapter
 * behind this single boundary.</p>
 */
@Singleton
public final class DefaultExecutor {

    private final ExecutionRepository executionRepository;
    private final ExecutorService executorService;
    private final WorkerDispatcher workerDispatcher;
    private final ExternalTaskRepository externalTaskRepository;

    @Inject
    public DefaultExecutor(
        ExecutionRepository executionRepository,
        ExecutorService executorService,
        WorkerDispatcher workerDispatcher,
        ExternalTaskRepository externalTaskRepository
    ) {
        this.executionRepository = executionRepository;
        this.executorService = executorService;
        this.workerDispatcher = workerDispatcher;
        this.externalTaskRepository = externalTaskRepository;
    }

    public <S extends Session<U>, U extends User> Execution execute(
        S session,
        DSLContext dsl,
        ExecutorContext context
    ) {
        requireRuntime(session, dsl, context);
        return drive(session, dsl, context, false);
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
        return drive(session, dsl, context, true);
    }

    public <S extends Session<U>, U extends User> Execution cancel(
        S session,
        DSLContext dsl,
        ExecutorContext context
    ) {
        requireRuntime(session, dsl, context);
        List<TaskRun> taskRunsToCancel =
            context.execution().unfinishedTaskRuns();
        executorService.cancel(context);
        persist(dsl, context);
        cancelWaitingResources(
            dsl,
            context,
            taskRunsToCancel
        );
        return context.execution().copy();
    }

    private <S extends Session<U>, U extends User> Execution drive(
        S session,
        DSLContext dsl,
        ExecutorContext context,
        boolean executionChanged
    ) {
        while (true) {
            boolean handled = executorService.handle(context);
            executionChanged = executionChanged || handled;
            List<TaskRun> pausedTaskRuns = context.takePausedTaskRuns();
            List<WorkerTask> workerTasks = context.takeWorkerTasks();
            if (executionChanged) {
                persist(dsl, context);
                executionChanged = false;
            }

            for (TaskRun pausedTaskRun : pausedTaskRuns) {
                createWaitingResource(dsl, context, pausedTaskRun);
            }

            if (workerTasks.isEmpty()) {
                return context.execution().copy();
            }

            for (WorkerTask workerTask : workerTasks) {
                executorService.dispatch(context, workerTask);
                persist(dsl, context);
                executionChanged = false;

                List<TaskRun> pausedBeforeResult =
                    context.execution().pausedTaskRuns();
                WorkerTaskResult result = workerDispatcher.dispatch(
                    session,
                    dsl,
                    workerTask
                );
                executorService.applyResult(context, result);
                executionChanged = true;
                if (result.targetState() == State.Type.TERMINATED) {
                    persist(dsl, context);
                    cancelWaitingResources(
                        dsl,
                        context,
                        pausedBeforeResult
                    );
                    return context.execution().copy();
                }
            }
        }
    }

    private void persist(
        DSLContext dsl,
        ExecutorContext context
    ) {
        executionRepository.save(dsl, context.execution());
    }

    private void cancelWaitingResources(
        DSLContext dsl,
        ExecutorContext context,
        List<TaskRun> taskRuns
    ) {
        for (TaskRun taskRun : taskRuns) {
            externalTaskRepository.findWaitingByTaskRunId(
                dsl,
                context.execution().companyId(),
                taskRun.id()
            ).ifPresent(externalTask -> {
                externalTask.cancel();
                externalTaskRepository.save(dsl, externalTask);
            });
        }
    }

    private void createWaitingResource(
        DSLContext dsl,
        ExecutorContext context,
        TaskRun taskRun
    ) {
        Task task = context.flow().findTask(taskRun.taskId())
            .orElseThrow(() -> new IllegalStateException(
                "TaskRun references a missing Task: " + taskRun.taskId()
            ));
        if (!(task instanceof OrchestrationTask orchestrationTask)
            || !orchestrationTask.pausesTaskRun()) {
            return;
        }
        String companyId = context.execution().companyId();
        if (externalTaskRepository.findWaitingByTaskRunId(
            dsl,
            companyId,
            taskRun.id()
        ).isPresent()) {
            throw new IllegalStateException(
                "Paused Orchestration TaskRun already has an ExternalTask: "
                    + taskRun.id()
            );
        }
        externalTaskRepository.save(
            dsl,
            ExternalTask.create(
                companyId,
                context.execution().id(),
                taskRun.id()
            )
        );
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
