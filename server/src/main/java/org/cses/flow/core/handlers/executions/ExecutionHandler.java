package org.cses.flow.core.handlers.executions;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.executions.TaskRunStatus;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.executor.ExecutorContext;
import org.cses.flow.executor.ExecutorService;
import org.cses.flow.executor.NextTask;
import org.cses.flow.worker.WorkerContext;
import org.cses.flow.worker.WorkerDispatcher;
import org.cses.flow.worker.WorkerTask;
import org.cses.flow.worker.WorkerTaskOutcome;
import org.cses.flow.worker.WorkerTaskResult;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Optional;
import java.util.Map;

/**
 * Owns one recoverable Execution lifecycle inside the current command
 * transaction, including the required intermediate aggregate flushes.
 */
@Singleton
public final class ExecutionHandler {

    private final ExecutionRepository executionRepository;
    private final ExecutorService executorService;
    private final WorkerDispatcher workerDispatcher;

    @Inject
    public ExecutionHandler(
        ExecutionRepository executionRepository,
        ExecutorService executorService,
        WorkerDispatcher workerDispatcher
    ) {
        this.executionRepository = executionRepository;
        this.executorService = executorService;
        this.workerDispatcher = workerDispatcher;
    }

    public <S extends Session<U>, U extends User>
        Execution handle(ExecutorContext<S, U> context) {

        // The new aggregate is inserted before handleNext creates and flushes
        // its first TaskRun for Task-owned foreign keys.
        executionRepository.save(context.dsl(), context.execution());
        return drive(context, false);
    }

    private <S extends Session<U>, U extends User>
        Execution drive(
            ExecutorContext<S, U> context,
            boolean initiallyDirty
        ) {

        boolean dirty = initiallyDirty;
        while (true) {
            Execution beforeNext = context.execution().copy();
            Optional<NextTask> next = executorService.handleNext(context);
            dirty = dirty
                || !sameState(beforeNext, context.execution());
            if (next.isEmpty()) {
                if (dirty) {
                    executionRepository.save(
                        context.dsl(),
                        context.execution()
                    );
                }
                return context.execution().copy();
            }

            NextTask nextTask = next.orElseThrow();
            executorService.dispatch(context, nextTask);
            executionRepository.save(context.dsl(), context.execution());
            dirty = false;

            WorkerTaskResult result = workerDispatcher.dispatch(
                workerContext(context, nextTask.task(), nextTask.taskRun())
            );
            executorService.applyResult(context, result);

            if (result.outcome() == WorkerTaskOutcome.FAILED) {
                executionRepository.save(
                    context.dsl(),
                    context.execution()
                );
                return context.execution().copy();
            }
            if (result.outcome() == WorkerTaskOutcome.COMPLETED) {
                dirty = true;
            }
        }
    }

    public <S extends Session<U>, U extends User>
        Execution cancel(ExecutorContext<S, U> context) {

        for (TaskRun taskRun : context.execution().taskRuns()) {
            if (taskRun.status() != TaskRunStatus.RUNNING
                && taskRun.status() != TaskRunStatus.CREATED) {
                continue;
            }
            Task task = context.flow()
                .findTask(taskRun.taskId())
                .orElseThrow(() -> new IllegalStateException(
                    "TaskRun references a missing Task: " + taskRun.taskId()
                ));
            workerDispatcher.cancel(
                workerContext(context, task, taskRun)
            );
        }
        executorService.cancel(context);
        executionRepository.save(context.dsl(), context.execution());
        return context.execution().copy();
    }

    public <S extends Session<U>, U extends User>
        Execution resume(
            ExecutorContext<S, U> context,
            String taskRunId,
            Map<String, ?> outputs
        ) {

        executorService.resume(context, taskRunId, outputs);
        return drive(context, true);
    }

    public <S extends Session<U>, U extends User>
        Execution continueExecution(ExecutorContext<S, U> context) {

        return drive(context, false);
    }

    private static boolean sameState(
        Execution left,
        Execution right
    ) {
        if (left.status() != right.status()
            || left.lockVersion() != right.lockVersion()) {
            return false;
        }
        java.util.List<TaskRun> leftRuns = left.taskRuns();
        java.util.List<TaskRun> rightRuns = right.taskRuns();
        if (leftRuns.size() != rightRuns.size()) {
            return false;
        }
        for (int index = 0; index < leftRuns.size(); index++) {
            TaskRun first = leftRuns.get(index);
            TaskRun second = rightRuns.get(index);
            if (!first.id().equals(second.id())
                || !first.taskId().equals(second.taskId())
                || !first.parentId().equals(second.parentId())
                || !first.inputs().equals(second.inputs())
                || first.status() != second.status()
                || !first.outputs().equals(second.outputs())
                || !first.error().equals(second.error())) {
                return false;
            }
        }
        return true;
    }

    private static <S extends Session<U>, U extends User>
        WorkerContext<S, U> workerContext(
            ExecutorContext<S, U> context,
            Task task,
            TaskRun taskRun
        ) {

        return new WorkerContext<>(
            context.session(),
            context.dsl(),
            new WorkerTask(
                context.execution().id(),
                taskRun.id(),
                task,
                taskRun.inputs()
            )
        );
    }
}
