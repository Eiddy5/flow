package org.cses.flow.core.services.executions;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.commands.executions.CancelExecutionCommand;
import org.cses.flow.core.commands.executions.CreateExecutionCommand;
import org.cses.flow.core.commands.executions.ContinueExecutionCommand;
import org.cses.flow.core.commands.shared.CommandExecutor;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.executions.TaskRunStatus;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.shared.WorkflowException;
import org.cses.flow.core.handlers.executions.ExecutionHandler;
import org.cses.flow.core.queries.executions.ExecutionQueryHandler;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.services.shared.SessionValidation;
import org.cses.flow.executor.ExecutorContext;
import org.jooq.DSLContext;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Singleton
public final class ExecutionService {

    private final CommandExecutor commandExecutor;
    private final ExecutionQueryHandler queryHandler;
    private final ExecutionRepository executionRepository;
    private final FlowRepository flowRepository;
    private final ExecutionHandler executionHandler;

    @Inject
    public ExecutionService(
        CommandExecutor commandExecutor,
        ExecutionQueryHandler queryHandler,
        ExecutionRepository executionRepository,
        FlowRepository flowRepository,
        ExecutionHandler executionHandler
    ) {
        this.commandExecutor = commandExecutor;
        this.queryHandler = queryHandler;
        this.executionRepository = executionRepository;
        this.flowRepository = flowRepository;
        this.executionHandler = executionHandler;
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

    /**
     * Internal continuation entry used by trigger-specific command handlers.
     * It deliberately reuses the current command Session and DSL transaction.
     */
    public <S extends Session<U>, U extends User>
        Execution resume(
            S session,
            DSLContext dsl,
            String executionId,
            String taskRunId,
            Map<String, ?> outputs
        ) {

        String companyId = SessionValidation.requireCompanyId(session);
        Execution execution = executionRepository.findById(
            dsl,
            companyId,
            executionId
        ).orElseThrow(() ->
            new WorkflowException("Execution does not exist: " + executionId)
        );
        Flow flow = flowRepository.findById(
            dsl,
            companyId,
            execution.flowId(),
            execution.flowReversion()
        ).orElseThrow(() ->
            new WorkflowException(
                "Flow does not exist: " + execution.flowId()
            )
        );
        TaskRun taskRun = execution.requireTaskRun(taskRunId);
        if (taskRun.status() != TaskRunStatus.RUNNING) {
            throw new WorkflowException(
                "Only a RUNNING TaskRun can be resumed: " + taskRunId
            );
        }
        Task task = flow.findTask(taskRun.taskId()).orElseThrow(() ->
            new WorkflowException(
                "Task definition does not exist: " + taskRun.taskId()
            )
        );
        if (!"PAUSE".equals(task.type())) {
            throw new WorkflowException(
                "Only a PAUSE TaskRun can be resumed: " + taskRunId
            );
        }
        Map<String, ?> normalizedOutputs =
            outputs == null ? Map.of() : outputs;
        Set<String> unsupported = new java.util.LinkedHashSet<>(
            normalizedOutputs.keySet()
        );
        unsupported.removeIf(task::declaresOutput);
        if (!unsupported.isEmpty()) {
            throw new WorkflowException(
                "PAUSE outputs were not declared by Task: " + unsupported
            );
        }

        return executionHandler.resume(
            new ExecutorContext<>(session, dsl, flow, execution),
            taskRunId,
            normalizedOutputs
        );
    }

}
