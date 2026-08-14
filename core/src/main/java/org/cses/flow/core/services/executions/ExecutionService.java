package org.cses.flow.core.services.executions;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.cses.flow.core.commands.CommandExecutor;
import org.cses.flow.core.commands.executions.CancelExecutionCommand;
import org.cses.flow.core.commands.executions.ContinueExecutionCommand;
import org.cses.flow.core.commands.executions.CreateExecutionCommand;
import org.cses.flow.core.commands.executions.ResumeExecutionCommand;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.queries.executions.ExecutionQueryHandler;
import org.cses.flow.core.queries.flows.FlowQueryHandler;
import org.cses.flow.executor.commands.Create;
import org.cses.flow.executor.commands.ExecutorCommand;
import org.cses.flow.queues.DispatchQueue;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Singleton
public final class ExecutionService {

    private final CommandExecutor commandExecutor;
    private final ExecutionQueryHandler queryHandler;
    private final FlowQueryHandler flowQueryHandler;
    private final DispatchQueue<ExecutorCommand> executorCommandQueue;

    @Inject
    public ExecutionService(
            CommandExecutor commandExecutor,
            ExecutionQueryHandler queryHandler,
            FlowQueryHandler flowQueryHandler,
            @Named(ExecutorCommand.QUEUE_NAME)
            DispatchQueue<ExecutorCommand> executorCommandQueue
    ) {
        this.commandExecutor = commandExecutor;
        this.queryHandler = queryHandler;
        this.flowQueryHandler = flowQueryHandler;
        this.executorCommandQueue = executorCommandQueue;
    }

    /**
     * Creates an Executor {@link Create} command and submits it to the durable
     * command Queue. Returning means Queue acceptance, not workflow creation
     * or completion.
     */
    public <S extends Session<U>, U extends User> Execution create(
            S session,
            String flowId
    ) {
        return create(session, flowId, Map.of());
    }

    public <S extends Session<U>, U extends User> Execution create(
            S session,
            String flowId,
            Map<String, ?> inputs
    ) {
        Flow flow = requireLatestFlow(session, flowId);
        Map<String, Object> normalizedInputs = flow.normalizeInputs(inputs);
        Execution accepted = Execution.create(
                flow.companyId(),
                flow.id(),
                flow.reversion()
        );
        executorCommandQueue.emit(Create.from(
                session,
                accepted,
                normalizedInputs
        ));
        return accepted.copy();
    }

    /**
     * Creates and persists a CREATED Execution without dispatching its first
     * Task. A host application can bind its business aggregate to the
     * generated execution id before calling {@link #continueExecution}.
     */
    public <S extends Session<U>, U extends User> Execution createPending(
            S session,
            String flowId
    ) {
        Execution execution = commandExecutor.execute(
                session,
                new CreateExecutionCommand(flowId)
        );
        return execution.copy();
    }

    /**
     * Idempotently materializes a pending Execution for a trusted host that
     * has already persisted the stable id and exact Flow reversion. This is
     * not a historical-version selector for ordinary new executions.
     */
    public <S extends Session<U>, U extends User> Execution createPending(
            S session,
            String executionId,
            String flowId,
            long flowReversion
    ) {
        return commandExecutor.execute(
                session,
                new CreateExecutionCommand(
                        executionId,
                        flowId,
                        flowReversion
                )
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

    /**
     * Atomically stages the durable start command for a pending CREATED
     * Execution. Returning means Queue acceptance, not workflow completion.
     */
    public <S extends Session<U>, U extends User> Execution continueExecution(
            S session,
            String executionId
    ) {
        return continueExecution(session, executionId, Map.of());
    }

    public <S extends Session<U>, U extends User> Execution continueExecution(
            S session,
            String executionId,
            Map<String, ?> inputs
    ) {
        Execution current = queryHandler.execution(session, executionId)
                .orElseThrow(() -> new WorkflowException(
                        "Execution does not exist: " + executionId
                ));
        Map<String, Object> normalizedInputs = current.state().is(
                State.Type.CREATED
        )
                ? flowQueryHandler.flow(
                        session,
                        current.flowId(),
                        current.flowReversion()
                )
                .orElseThrow(() -> new WorkflowException(
                        "Flow does not exist: " + current.flowId()
                                + "@" + current.flowReversion()
                ))
                .normalizeInputs(inputs)
                : Map.of();
        return commandExecutor.execute(
                session,
                new ContinueExecutionCommand(executionId),
                (pending, dsl) -> {
                    if (pending.state().is(State.Type.CREATED)) {
                        executorCommandQueue.emit(
                                Create.from(session, pending, normalizedInputs)
                                        .inTransaction(dsl)
                        );
                    }
                }
        ).copy();
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

    private <S extends Session<U>, U extends User> Flow requireLatestFlow(
            S session,
            String flowId
    ) {
        return flowQueryHandler.latestFlow(session, flowId)
                .orElseThrow(() -> new WorkflowException(
                        "Flow does not exist: " + flowId
                ));
    }

}
