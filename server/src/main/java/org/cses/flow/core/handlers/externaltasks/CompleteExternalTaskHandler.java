package org.cses.flow.core.handlers.externaltasks;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import org.cses.flow.core.commands.externaltasks.CompleteExternalTaskCommand;
import org.cses.flow.core.commands.shared.CommandContext;
import org.cses.flow.core.commands.shared.CommandHandler;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.externaltasks.ExternalTask;
import org.cses.flow.core.exceptions.shared.WorkflowException;
import org.cses.flow.core.repositories.externaltasks.ExternalTaskRepository;
import org.cses.flow.core.services.executions.ExecutionService;
import org.cses.flow.core.services.shared.SessionValidation;
import org.paas.session.Session;
import org.paas.session.User;

@Singleton
public final class CompleteExternalTaskHandler implements CommandHandler<
    Session<User>,
    User,
    Execution,
    CompleteExternalTaskCommand
> {

    private final ExternalTaskRepository externalTaskRepository;
    private final Provider<ExecutionService> executionService;

    @Inject
    public CompleteExternalTaskHandler(
        ExternalTaskRepository externalTaskRepository,
        Provider<ExecutionService> executionService
    ) {
        this.externalTaskRepository = externalTaskRepository;
        this.executionService = executionService;
    }

    @Override
    public Class<CompleteExternalTaskCommand> commandType() {
        return CompleteExternalTaskCommand.class;
    }

    @Override
    public Execution handle(
        CommandContext<
            Session<User>,
            User,
            Execution,
            CompleteExternalTaskCommand
        > context
    ) {
        String companyId = SessionValidation.requireCompanyId(
            context.getSession()
        );
        ExternalTask externalTask = externalTaskRepository.findById(
            context.getDsl(),
            companyId,
            context.getCommand().externalTaskId()
        ).orElseThrow(() -> new WorkflowException(
            "ExternalTask does not exist: "
                + context.getCommand().externalTaskId()
        ));
        externalTask.complete(context.getCommand().outputs());
        externalTaskRepository.save(context.getDsl(), externalTask);

        return executionService.get().resume(
            context.getSession(),
            context.getDsl(),
            externalTask.executionId(),
            externalTask.taskRunId(),
            externalTask.outputs()
        );
    }
}
