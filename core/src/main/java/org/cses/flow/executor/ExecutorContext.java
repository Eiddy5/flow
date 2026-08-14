package org.cses.flow.executor;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.worker.WorkerTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Mutable work unit for one recoverable executor scheduling cycle.
 *
 * <p>The exact deployed Flow and its Execution are the authoritative inputs.
 * All other fields are transient deltas produced during this cycle; the
 * context itself is never persisted.</p>
 */
public final class ExecutorContext {

    private final Execution execution;
    private final Flow flow;
    private final Map<String, Object> flowInputs;
    private final List<TaskRun> nexts;
    private final List<WorkerTask> workerTasks;
    private final List<String> orchestrationCompletions;
    private final List<State.Type> states;
    private boolean executionUpdated;

    public ExecutorContext(Flow flow, Execution execution) {
        this(flow, execution, restoredFlowInputs(execution));
    }

    public ExecutorContext(
        Flow flow,
        Execution execution,
        Map<String, ?> flowInputs
    ) {
        this.flow = Objects.requireNonNull(flow, "flow");
        this.execution = Objects.requireNonNull(execution, "execution");
        this.flowInputs = immutableFlowInputs(flowInputs);
        if (!flow.identifiedBy(execution.flowId())
            || !flow.companyId().equals(execution.companyId())
            || flow.reversion() != execution.flowReversion()) {
            throw new IllegalArgumentException(
                "Execution does not belong to the exact Flow reversion"
            );
        }
        this.nexts = new ArrayList<>();
        this.workerTasks = new ArrayList<>();
        this.orchestrationCompletions = new ArrayList<>();
        this.states = new ArrayList<>();
        this.states.add(execution.state().current());
    }

    public Flow flow() {
        return flow;
    }

    public Execution execution() {
        return execution;
    }

    public Map<String, Object> flowInputs() {
        return flowInputs;
    }

    public Map<String, Object> flowVariables() {
        return flow.variables();
    }

    public List<State.Type> states() {
        return List.copyOf(states);
    }

    public List<TaskRun> nexts() {
        return List.copyOf(nexts);
    }

    public List<WorkerTask> workerTasks() {
        return List.copyOf(workerTasks);
    }

    public List<String> orchestrationCompletions() {
        return List.copyOf(orchestrationCompletions);
    }

    public boolean canBeProcessed() {
        return !execution.state().isTerminal()
            && !execution.state().is(State.Type.PAUSED);
    }

    public boolean canScheduleNext() {
        return execution.state().is(State.Type.CREATED)
            || execution.state().is(State.Type.RUNNING);
    }

    void stageNexts(List<TaskRun> plannedNexts) {
        Objects.requireNonNull(plannedNexts, "plannedNexts");
        nexts.clear();
        nexts.addAll(plannedNexts);
    }

    List<TaskRun> takeNexts() {
        List<TaskRun> staged = List.copyOf(nexts);
        nexts.clear();
        return staged;
    }

    void clearNexts() {
        nexts.clear();
    }

    void stageWorkerTask(WorkerTask workerTask) {
        workerTasks.add(Objects.requireNonNull(
            workerTask,
            "workerTask"
        ));
    }

    void stageWorkerTasks(List<WorkerTask> plannedWorkerTasks) {
        Objects.requireNonNull(plannedWorkerTasks, "WorkerTasks");
        workerTasks.addAll(plannedWorkerTasks);
    }

    List<WorkerTask> takeWorkerTasks() {
        List<WorkerTask> staged = List.copyOf(workerTasks);
        workerTasks.clear();
        return staged;
    }

    void stageOrchestrationCompletions(List<String> taskRunIds) {
        Objects.requireNonNull(taskRunIds, "TaskRun ids");
        orchestrationCompletions.clear();
        orchestrationCompletions.addAll(taskRunIds);
    }

    List<String> takeOrchestrationCompletions() {
        List<String> staged = List.copyOf(orchestrationCompletions);
        orchestrationCompletions.clear();
        return staged;
    }

    boolean takeExecutionUpdated() {
        boolean updated = executionUpdated;
        executionUpdated = false;
        return updated;
    }

    void captureState() {
        executionUpdated = true;
        State.Type current = execution.state().current();
        if (states.getLast() != current) {
            states.add(current);
        }
    }

    public ExecutorContext restart(Execution execution) {
        executionUpdated = true;
        State.Type current = execution.state().current();
        if (states.getLast() != current) {
            states.add(current);
        }
        return this;
    }

    private static Map<String, Object> restoredFlowInputs(
        Execution execution
    ) {
        if (execution == null) {
            return Map.of();
        }
        for (TaskRun taskRun : execution.taskRuns()) {
            Object value = taskRun.inputs().get("flowInputs");
            if (!(value instanceof Map<?, ?> values)) {
                continue;
            }
            Map<String, Object> restored = new LinkedHashMap<>();
            values.forEach((key, input) -> restored.put(
                String.valueOf(key),
                Objects.requireNonNull(input, "Flow input value")
            ));
            return Map.copyOf(restored);
        }
        return Map.of();
    }

    private static Map<String, Object> immutableFlowInputs(
        Map<String, ?> values
    ) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copied = new LinkedHashMap<>();
        values.forEach((key, value) -> copied.put(
            Objects.requireNonNull(key, "Flow input key"),
            Objects.requireNonNull(value, "Flow input value")
        ));
        return Map.copyOf(copied);
    }
}
