package org.cses.flow.extensions.workers;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.externaltasks.ExternalTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.repositories.externaltasks.ExternalTaskRepository;
import org.cses.flow.core.services.shared.SessionValidation;
import org.cses.flow.extensions.tasks.PauseTask;
import org.cses.flow.worker.WorkerContext;
import org.cses.flow.worker.WorkerTaskHandler;
import org.cses.flow.worker.WorkerTaskResult;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Map;

/**
 * Creates and owns the first-phase external resume trigger for a PAUSE Task.
 */
@Singleton
public final class PauseTaskHandler implements WorkerTaskHandler {

    private final ExternalTaskRepository externalTaskRepository;

    @Inject
    public PauseTaskHandler(
        ExternalTaskRepository externalTaskRepository
    ) {
        this.externalTaskRepository = externalTaskRepository;
    }

    @Override
    public boolean supports(Task task) {
        return task instanceof PauseTask;
    }

    @Override
    public <S extends Session<U>, U extends User> WorkerTaskResult execute(
        WorkerContext<S, U> context
    ) {
        String companyId = SessionValidation.requireCompanyId(
            context.session()
        );
        var workerTask = context.workerTask();
        if (externalTaskRepository.findWaitingByTaskRunId(
            context.dsl(),
            companyId,
            workerTask.taskRunId()
        ).isPresent()) {
            throw new IllegalStateException(
                "PAUSE TaskRun already has a waiting ExternalTask: "
                    + workerTask.taskRunId()
            );
        }
        ExternalTask externalTask = ExternalTask.create(
            companyId,
            workerTask.executionId(),
            workerTask.taskRunId(),
            workerTask.task().outputs().stream()
                .map(output -> output.getKey())
                .collect(java.util.stream.Collectors.toUnmodifiableSet())
        );
        externalTaskRepository.save(context.dsl(), externalTask);
        return WorkerTaskResult.running(
            workerTask,
            Map.of("externalTaskId", externalTask.id().toString())
        );
    }

    @Override
    public <S extends Session<U>, U extends User> void cancel(
        WorkerContext<S, U> context
    ) {
        String companyId = SessionValidation.requireCompanyId(
            context.session()
        );
        externalTaskRepository.findWaitingByTaskRunId(
            context.dsl(),
            companyId,
            context.workerTask().taskRunId()
        ).ifPresent(externalTask -> {
            externalTask.cancel();
            externalTaskRepository.save(context.dsl(), externalTask);
        });
    }

}
