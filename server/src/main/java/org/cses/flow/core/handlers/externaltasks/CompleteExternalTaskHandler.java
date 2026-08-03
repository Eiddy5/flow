package org.cses.flow.core.handlers.externaltasks;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.commands.externaltasks.CompleteExternalTaskCommand;
import org.cses.flow.core.commands.CommandContext;
import org.cses.flow.core.commands.CommandHandler;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.externaltasks.ExternalTask;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.handlers.executions.ResumeExecutionHandler;
import org.cses.flow.core.repositories.externaltasks.ExternalTaskRepository;
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
    private final ResumeExecutionHandler resumeExecutionHandler;

    @Inject
    public CompleteExternalTaskHandler(
        ExternalTaskRepository externalTaskRepository,
        ResumeExecutionHandler resumeExecutionHandler
    ) {
        this.externalTaskRepository = externalTaskRepository;
        this.resumeExecutionHandler = resumeExecutionHandler;
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
        Execution execution = resumeExecutionHandler.resume(
            context.getSession(),
            context.getDsl(),
            externalTask.executionId(),
            externalTask.taskRunId(),
            context.getCommand().outputs()
        );
        externalTask.complete(
            execution.requireTaskRun(externalTask.taskRunId()).outputs()
        );
        externalTaskRepository.save(context.getDsl(), externalTask);
        return execution;
    }
}
