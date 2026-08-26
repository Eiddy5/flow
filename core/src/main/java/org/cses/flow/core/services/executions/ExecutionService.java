package org.cses.flow.core.services.executions;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.services.executions.queries.ExecutionQueryHandler;
import org.cses.flow.core.services.flows.queries.FlowQueryHandler;
import org.cses.flow.executor.commands.Create;
import org.cses.flow.executor.commands.Cancel;
import org.cses.flow.executor.commands.ExecutionCommand;
import org.cses.flow.executor.commands.Resume;
import org.cses.flow.extensions.flow.Pause;
import org.cses.flow.queues.DispatchQueue;
import org.paas.common.util.StringUtil;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Singleton
public final class ExecutionService {

    private final ExecutionQueryHandler queryHandler;
    private final FlowQueryHandler flowQueryHandler;
    private final DispatchQueue<ExecutionCommand> executorCommandQueue;

    @Inject
    public ExecutionService(
            ExecutionQueryHandler queryHandler,
            FlowQueryHandler flowQueryHandler,
            @Named(ExecutionCommand.QUEUE_NAME)
            DispatchQueue<ExecutionCommand> executorCommandQueue
    ) {
        this.queryHandler = queryHandler;
        this.flowQueryHandler = flowQueryHandler;
        this.executorCommandQueue = executorCommandQueue;
    }

    /**
     * Creates an Executor {@link Create} command and submits it to the durable
     * command Queue. The returned command is the Queue acceptance receipt;
     * the Execution is materialized and driven asynchronously by the command
     * consumer.
     */
    public <S extends Session<U>, U extends User> Create create(
            S session,
            String flowKey
    ) {
        return create(session, flowKey, Map.of());
    }

    public <S extends Session<U>, U extends User> Create create(
            S session,
            String flowKey,
            Map<String, ?> inputs
    ) {
        Flow flow = requireLatestFlow(session, flowKey);
        if (flow.deleted()) {
            throw new WorkflowException(
                    "Only an undeleted Flow can start an Execution: "
                            + flow.key() + "@" + flow.reversion()
            );
        }
        Map<String, Object> normalizedInputs = flow.normalizeInputs(inputs);
        String executionId = StringUtil.newId();
        Create command = Create.from(
                session,
                executionId,
                flow.key(),
                flow.reversion(),
                normalizedInputs
        );
        executorCommandQueue.emit(command);
        return command;
    }

    public <S extends Session<U>, U extends User> Execution cancel(
            S session,
            String executionId
    ) {
        Execution current = queryHandler.execution(session, executionId)
                .orElseThrow(() -> new WorkflowException(
                        "Execution does not exist: " + executionId
                ));
        if (current.isTerminal()) {
            throw new WorkflowException(
                    "Cannot cancel terminal Execution: " + executionId
            );
        }
        if (current.state().is(State.Type.KILLING)) {
            throw new WorkflowException(
                    "Execution is already KILLING: " + executionId
            );
        }
        executorCommandQueue.emit(Cancel.from(session, executionId));
        return current.copy();
    }

    /**
     * Validates and submits one durable Resume command for an exact paused
     * TaskRun. Returning means Queue acceptance, not workflow completion.
     */
    public <S extends Session<U>, U extends User> Execution resume(
            S session,
            String executionId,
            String taskRunId,
            Map<String, ?> outputs
    ) {
        Execution current = queryHandler.execution(session, executionId)
                .orElseThrow(() -> new WorkflowException(
                        "Execution does not exist: " + executionId
                ));
        Map<String, Object> normalizedOutputs = validateResume(
                session,
                current,
                taskRunId,
                outputs
        );
        executorCommandQueue.emit(Resume.from(
                session,
                executionId,
                taskRunId,
                normalizedOutputs
        ));
        return current.copy();
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
            String flowKey
    ) {
        return flowQueryHandler.latestFlow(session, flowKey)
                .orElseThrow(() -> new WorkflowException(
                        "Flow does not exist: " + flowKey
                ));
    }

    private <S extends Session<U>, U extends User>
    Map<String, Object> validateResume(
            S session,
            Execution execution,
            String taskRunId,
            Map<String, ?> outputs
    ) {
        if (!execution.state().is(State.Type.PAUSED)) {
            throw new WorkflowException(
                    "Only a PAUSED Execution can resume a Pause TaskRun: "
                            + execution.id()
            );
        }
        Flow flow = flowQueryHandler.flow(
                        session,
                        execution.flowKey(),
                        execution.flowVersion()
                )
                .orElseThrow(() -> new WorkflowException(
                        "Flow does not exist: " + execution.flowKey()
                                + "@" + execution.flowVersion()
                ));
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
        return pause.validateResume(outputs);
    }

}
