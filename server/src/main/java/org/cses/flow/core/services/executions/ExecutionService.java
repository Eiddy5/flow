package org.cses.flow.core.services.executions;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.commands.executions.CancelExecutionCommand;
import org.cses.flow.core.commands.executions.CreateExecutionCommand;
import org.cses.flow.core.commands.executions.ContinueExecutionCommand;
import org.cses.flow.core.commands.executions.ResumeExecutionCommand;
import org.cses.flow.core.commands.CommandExecutor;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.queries.executions.ExecutionQueryHandler;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Singleton
public final class ExecutionService {

    private final CommandExecutor commandExecutor;
    private final ExecutionQueryHandler queryHandler;

    @Inject
    public ExecutionService(
        CommandExecutor commandExecutor,
        ExecutionQueryHandler queryHandler
    ) {
        this.commandExecutor = commandExecutor;
        this.queryHandler = queryHandler;
    }

    public <S extends Session<U>, U extends User> Execution create(
        S session,
        String flowId
    ) {
        return commandExecutor.execute(
            session,
            new CreateExecutionCommand(flowId)
        );
    }

    public <S extends Session<U>, U extends User> Execution cancel(
        S session,
        String executionId
    ) {
        return commandExecutor.execute(
            session,
            new CancelExecutionCommand(executionId)
        );
    }

    public <S extends Session<U>, U extends User> Execution continueExecution(
        S session,
        String executionId
    ) {
        return commandExecutor.execute(
            session,
            new ContinueExecutionCommand(executionId)
        );
    }

    /**
     * Resumes one PAUSED TaskRun and drives the Execution to its next
     * stable state.
     */
    public <S extends Session<U>, U extends User> Execution resume(
        S session,
        String executionId,
        String taskRunId,
        Map<String, ?> outputs
    ) {
        return commandExecutor.execute(
            session,
            new ResumeExecutionCommand(executionId, taskRunId, outputs)
        );
    }

    public <S extends Session<U>, U extends User>
        Optional<Execution> execution(
            S session,
            String executionId
        ) {

        return queryHandler.execution(session, executionId);
    }

    public <S extends Session<U>, U extends User>
        List<Execution> executions(S session) {

        return queryHandler.executions(session);
    }
}
