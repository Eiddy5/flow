package org.cses.flow.executor;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.worker.WorkerTask;

import java.util.ArrayList;
import java.util.List;
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
    private final List<TaskRun> nexts;
    private final List<WorkerTask> workerTasks;
    private final List<TaskRun> pausedTaskRuns;
    private final List<String> orchestrationCompletions;
    private final List<State.Type> states;

    public ExecutorContext(Flow flow, Execution execution) {
        this.flow = Objects.requireNonNull(flow, "flow");
        this.execution = Objects.requireNonNull(execution, "execution");
        if (!flow.id().equals(execution.flowId())
            || !flow.companyId().equals(execution.companyId())
            || flow.reversion() != execution.flowReversion()) {
            throw new IllegalArgumentException(
                "Execution does not belong to the exact Flow reversion"
            );
        }
        this.nexts = new ArrayList<>();
        this.workerTasks = new ArrayList<>();
        this.pausedTaskRuns = new ArrayList<>();
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

    public List<State.Type> states() {
        return List.copyOf(states);
    }

    public List<TaskRun> nexts() {
        return List.copyOf(nexts);
    }

    public List<WorkerTask> workerTasks() {
        return List.copyOf(workerTasks);
    }

    public List<TaskRun> pausedTaskRuns() {
        return List.copyOf(pausedTaskRuns);
    }

    public List<String> orchestrationCompletions() {
        return List.copyOf(orchestrationCompletions);
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

    void stageWorkerTask(WorkerTask workerTask) {
        workerTasks.add(Objects.requireNonNull(
            workerTask,
            "workerTask"
        ));
    }

    List<WorkerTask> takeWorkerTasks() {
        List<WorkerTask> staged = List.copyOf(workerTasks);
        workerTasks.clear();
        return staged;
    }

    void stagePausedTaskRun(TaskRun taskRun) {
        pausedTaskRuns.add(Objects.requireNonNull(
            taskRun,
            "taskRun"
        ));
    }

    List<TaskRun> takePausedTaskRuns() {
        List<TaskRun> staged = List.copyOf(pausedTaskRuns);
        pausedTaskRuns.clear();
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

    void captureState() {
        State.Type current = execution.state().current();
        if (states.getLast() != current) {
            states.add(current);
        }
    }
}
