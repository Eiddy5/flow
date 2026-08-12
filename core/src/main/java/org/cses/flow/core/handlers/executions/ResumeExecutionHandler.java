package org.cses.flow.core.handlers.executions;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.commands.CommandContext;
import org.cses.flow.core.commands.CommandHandler;
import org.cses.flow.core.commands.executions.ResumeExecutionCommand;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.handlers.flows.FlowHandlerSupport;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.services.shared.SessionValidation;
import org.cses.flow.executor.ExecutionRunner;
import org.cses.flow.executor.ExecutorContext;
import org.cses.flow.extensions.flow.Pause;
import org.jooq.DSLContext;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Map;

/**
 * Owns the public Execution resume use case inside the current command
 * transaction.
 */
@Singleton
public final class ResumeExecutionHandler implements CommandHandler<
    Session<User>,
    User,
    Execution,
    ResumeExecutionCommand
> {

    private final ExecutionRepository executionRepository;
    private final FlowRepository flowRepository;
    private final ExecutionRunner executionRunner;

    @Inject
    public ResumeExecutionHandler(
        ExecutionRepository executionRepository,
        FlowRepository flowRepository,
        ExecutionRunner executionRunner
    ) {
        this.executionRepository = executionRepository;
        this.flowRepository = flowRepository;
        this.executionRunner = executionRunner;
    }

    @Override
    public Class<ResumeExecutionCommand> commandType() {
        return ResumeExecutionCommand.class;
    }

    @Override
    public Execution handle(
        CommandContext<
            Session<User>,
            User,
            Execution,
            ResumeExecutionCommand
        > context
    ) {
        ResumeExecutionCommand command = context.getCommand();
        return resumeInCurrentTransaction(
            context.getSession(),
            context.getDsl(),
            command.executionId(),
            command.taskRunId(),
            command.outputs()
        );
    }

    private <S extends Session<U>, U extends User> Execution
        resumeInCurrentTransaction(
        S session,
        DSLContext dsl,
        String executionId,
        String taskRunId,
        Map<String, ?> outputs
    ) {
        String companyId = SessionValidation.requireCompanyId(session);
        Execution execution = executionRepository.lockById(
            dsl,
            companyId,
            executionId
        ).orElseThrow(() -> new WorkflowException(
            "Execution does not exist: " + executionId
        ));
        if (!execution.state().is(State.Type.PAUSED)) {
            throw new WorkflowException(
                "Only a PAUSED Execution can resume a Pause TaskRun: "
                    + execution.id()
            );
        }
        Flow flow = FlowHandlerSupport.requireFlow(
            flowRepository,
            dsl,
            companyId,
            execution.flowId(),
            execution.flowReversion()
        );
        TaskRun taskRun = execution.requireTaskRun(taskRunId);
        if (!taskRun.state().is(State.Type.PAUSED)) {
            throw new WorkflowException(
                "Only a PAUSED TaskRun can be resumed: " + taskRun.id()
            );
        }
        Task task = flow.findTask(taskRun.taskId()).orElseThrow(() ->
            new WorkflowException(
                "Task definition does not exist: " + taskRun.taskId()
            )
        );
        if (!(task instanceof Pause pause) || !pause.pausesTaskRun()) {
            throw new WorkflowException(
                "Only a paused Orchestration TaskRun can be resumed: "
                    + taskRun.id()
            );
        }
        Map<String, Object> normalizedOutputs = pause.validateResume(outputs);

        return executionRunner.resume(
            session,
            dsl,
            new ExecutorContext(flow, execution),
            taskRun.id(),
            normalizedOutputs
        );
    }
}
