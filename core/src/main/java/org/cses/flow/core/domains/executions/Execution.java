package org.cses.flow.core.domains.executions;

import org.cses.flow.core.domains.Lockable;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.exceptions.WorkflowException;
import org.paas.common.util.StringUtil;

import java.util.*;

/**
 * Aggregate root for one complete Flow start instance.
 */
public final class Execution implements Lockable<Execution> {

    private final String id;
    private final String companyId;
    private final String flowId;
    private final long flowReversion;
    private final List<TaskRun> taskRuns;
    private final boolean persisted;
    private State state;
    private long lockVersion;
    private boolean modified;

    private Execution(String id, String companyId, String flowId, long flowReversion, boolean persisted) {
        this.id = requireText(id, "Execution id");
        this.companyId = requireText(companyId, "Company id");
        this.flowId = requireText(flowId, "Flow id");
        if (flowReversion < 1) {
            throw new IllegalArgumentException("Flow reversion must be positive");
        }
        this.flowReversion = flowReversion;
        this.taskRuns = new ArrayList<>();
        this.state = State.created();
        this.persisted = persisted;
    }

    public static Execution create(String companyId, String flowId, long flowReversion) {
        return new Execution(StringUtil.newId(), companyId, flowId, flowReversion, false);
    }

    /**
     * Rehydrates a complete aggregate from a trusted persistence adapter.
     */
    public static Execution rehydrate(String id, String companyId, String flowId, long flowReversion, State state, long lockVersion, List<TaskRun> taskRuns) {
        if (lockVersion < 0) {
            throw new IllegalArgumentException("Execution lock version must not be negative");
        }
        Execution execution = new Execution(id, companyId, flowId, flowReversion, true);
        execution.state = Objects.requireNonNull(state, "Execution state");
        execution.lockVersion = lockVersion;
        if (taskRuns != null) {
            taskRuns.stream().map(TaskRun::copy).forEach(execution.taskRuns::add);
        }
        execution.validateRehydratedState();
        return execution;
    }

    private Execution(Execution source) {
        this.id = source.id;
        this.companyId = source.companyId;
        this.flowId = source.flowId;
        this.flowReversion = source.flowReversion;
        this.taskRuns = source.taskRuns.stream().map(TaskRun::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        this.state = source.state;
        this.lockVersion = source.lockVersion;
        this.persisted = source.persisted;
        this.modified = source.modified;
    }

    public String id() {
        return id;
    }

    @Override
    public String identifier() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public String flowId() {
        return flowId;
    }

    public long flowReversion() {
        return flowReversion;
    }

    public State state() {
        return state;
    }

    @Override
    public long lockVersion() {
        return lockVersion;
    }

    @Override
    public Execution lock() {
        lockVersion++;
        return this;
    }

    public List<TaskRun> taskRuns() {
        return List.copyOf(taskRuns);
    }

    public Optional<TaskRun> findTaskRun(String taskRunId) {
        String normalizedId = requireText(taskRunId, "TaskRun id");
        return taskRuns.stream()
            .filter(taskRun -> taskRun.identifiedBy(normalizedId))
            .findFirst();
    }

    public List<TaskRun> taskRunsForTask(String taskId) {
        String normalizedId = requireText(taskId, "Task id");
        return taskRuns.stream().filter(taskRun -> taskRun.taskId().equals(normalizedId)).toList();
    }

    public Optional<TaskRun> latestTaskRunForTask(String taskId) {
        List<TaskRun> matches = taskRunsForTask(taskId);
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.getLast());
    }

    /**
     * Locates the TaskRun for one concrete definition occurrence.
     */
    public Optional<TaskRun> taskRunForOccurrence(String taskId, String parentId, Integer iteration) {
        String normalizedTaskId = requireText(taskId, "Task id");
        return taskRuns.stream().filter(taskRun -> taskRun.taskId().equals(normalizedTaskId)).filter(taskRun -> Objects.equals(taskRun.parentId().orElse(null), parentId)).filter(taskRun -> Objects.equals(taskRun.iteration().isPresent() ? taskRun.iteration().getAsInt() : null, iteration)).findFirst();
    }

    public List<TaskRun> activeTaskRuns() {
        return taskRuns.stream().filter(TaskRun::isActive).toList();
    }

    public List<TaskRun> pausedTaskRuns() {
        return taskRuns.stream().filter(TaskRun::isPaused).toList();
    }

    public List<TaskRun> unfinishedTaskRuns() {
        return taskRuns.stream().filter(TaskRun::isUnfinished).toList();
    }

    public Optional<TaskRun> lastTaskRun() {
        return taskRuns.isEmpty() ? Optional.empty() : Optional.of(taskRuns.getLast());
    }

    public boolean isTerminal() {
        return state.isTerminal();
    }

    public void start() {
        requireState(State.Type.CREATED);
        markModified();
        state = state.running();
    }

    /**
     * Starts a new Execution and accepts its first scheduling batch atomically.
     */
    public void startWithTaskRuns(List<TaskRun> nexts) {
        requireState(State.Type.CREATED);
        List<TaskRun> accepted = validatedTaskRuns(nexts);
        if (accepted.isEmpty()) {
            throw new IllegalArgumentException("The first TaskRun batch must not be empty");
        }
        markModified();
        state = state.running();
        taskRuns.addAll(accepted);
    }

    public TaskRun createTaskRun(String taskId, String parentId, Map<String, ?> inputs) {
        TaskRun taskRun = TaskRun.create(taskId, parentId, inputs);
        addTaskRuns(List.of(taskRun));
        return requireTaskRun(taskRun.id());
    }

    /**
     * Accepts one validated scheduling batch into this aggregate.
     *
     * <p>The complete batch is validated before any TaskRun is appended, so a
     * rejected batch cannot partially change the Execution.</p>
     */
    public void addTaskRuns(List<TaskRun> nexts) {
        requireRunning();
        List<TaskRun> accepted = validatedTaskRuns(nexts);
        if (accepted.isEmpty()) {
            return;
        }
        markModified();
        taskRuns.addAll(accepted);
    }

    private List<TaskRun> validatedTaskRuns(List<TaskRun> nexts) {
        Objects.requireNonNull(nexts, "Next TaskRuns");
        if (nexts.isEmpty()) {
            return List.of();
        }

        HashSet<String> taskRunIds = taskRuns.stream().map(TaskRun::id).collect(java.util.stream.Collectors.toCollection(HashSet::new));
        HashSet<TaskOccurrence> occurrences = taskRuns.stream().map(TaskOccurrence::from).collect(java.util.stream.Collectors.toCollection(HashSet::new));
        List<TaskRun> accepted = new ArrayList<>(nexts.size());
        for (TaskRun next : nexts) {
            TaskRun taskRun = Objects.requireNonNull(next, "Next TaskRun");
            if (!taskRun.state().is(State.Type.CREATED) || !taskRun.outputs().isEmpty() || taskRun.error().isPresent()) {
                throw new WorkflowException("Only a new CREATED TaskRun can be scheduled: " + taskRun.id());
            }
            if (taskRunIds.contains(taskRun.id())) {
                throw new WorkflowException("Execution already has TaskRun id: " + taskRun.id());
            }
            if (!occurrences.add(TaskOccurrence.from(taskRun))) {
                throw new WorkflowException("Execution already has this TaskRun occurrence: " + taskRun.taskId());
            }
            taskRun.parentId().ifPresent(parentId -> {
                if (!taskRunIds.contains(parentId)) {
                    throw new WorkflowException("Parent TaskRun does not exist: " + parentId);
                }
            });
            taskRunIds.add(taskRun.id());
            accepted.add(taskRun.copy());
        }
        return accepted;
    }

    public void startTaskRun(String taskRunId) {
        requireRunning();
        TaskRun taskRun = requireTaskRun(taskRunId);
        markModified();
        taskRun.start();
    }

    public void succeedTaskRun(String taskRunId, Map<String, ?> outputs) {
        requireRunning();
        TaskRun taskRun = requireTaskRun(taskRunId);
        requireTaskRunState(taskRun, State.Type.RUNNING);
        markModified();
        taskRun.succeed(outputs);
    }

    public void warnTaskRun(String taskRunId, Map<String, ?> outputs) {
        requireRunning();
        TaskRun taskRun = requireTaskRun(taskRunId);
        requireTaskRunState(taskRun, State.Type.RUNNING);
        markModified();
        taskRun.warn(outputs);
    }

    public void pauseTaskRun(String taskRunId) {
        pauseTaskRuns(List.of(taskRunId));
    }

    public void pauseTaskRuns(List<String> taskRunIds) {
        requireRunning();
        Objects.requireNonNull(taskRunIds, "TaskRun ids");
        List<TaskRun> pausing = taskRunIds.stream().map(this::requireTaskRun).toList();
        if (new HashSet<>(pausing).size() != pausing.size()) {
            throw new IllegalArgumentException("TaskRun ids to pause must be unique");
        }
        pausing.forEach(taskRun -> requireTaskRunState(taskRun, State.Type.RUNNING));
        if (pausing.isEmpty()) {
            return;
        }
        markModified();
        pausing.forEach(TaskRun::pause);
    }

    public void resumeTaskRun(String taskRunId, Map<String, ?> outputs) {
        requireState(State.Type.PAUSED);
        TaskRun taskRun = requireTaskRun(taskRunId);
        requireTaskRunState(taskRun, State.Type.PAUSED);
        markModified();
        taskRun.resume(outputs);
        resumePausedAncestors(taskRun);
        state = state.restarted();
    }

    private void resumePausedAncestors(TaskRun taskRun) {
        Optional<String> parentId = taskRun.parentId();
        while (parentId.isPresent()) {
            TaskRun parent = requireTaskRun(parentId.orElseThrow());
            if (parent.state().is(State.Type.PAUSED)) {
                parent.resume(parent.outputs());
            }
            parentId = parent.parentId();
        }
    }

    public Execution restart() {
        requireState(State.Type.RESTARTED);
        markModified();
        state = state.running();
        return this;
    }

    public void failTaskRun(String taskRunId, String error) {
        requireRunning();
        TaskRun taskRun = requireTaskRun(taskRunId);
        requireTaskRunState(taskRun, State.Type.RUNNING);
        if (error == null || error.isBlank()) {
            throw new IllegalArgumentException("TaskRun error must not be blank");
        }
        markModified();
        taskRun.fail(error);
        taskRuns.stream().filter(other -> other != taskRun).filter(TaskRun::isUnfinished).forEach(TaskRun::kill);
        state = state.failed();
    }

    public void succeed() {
        requireRunning();
        if (!unfinishedTaskRuns().isEmpty()) {
            throw new WorkflowException("Execution has unfinished TaskRun: " + id);
        }
        markModified();
        state = state.success();
    }

    public void warn() {
        requireRunning();
        if (!unfinishedTaskRuns().isEmpty()) {
            throw new WorkflowException("Execution has unfinished TaskRun: " + id);
        }
        if (taskRuns.stream().noneMatch(taskRun -> taskRun.state().is(State.Type.WARNING))) {
            throw new WorkflowException("Execution cannot finish with WARNING without a warning " + "TaskRun: " + id);
        }
        markModified();
        state = state.warning();
    }

    public void pause() {
        requireRunning();
        if (!activeTaskRuns().isEmpty() || pausedTaskRuns().isEmpty()) {
            throw new WorkflowException("Execution can pause only at a stable Pause TaskRun: " + id);
        }
        markModified();
        state = state.paused();
    }

    public void beginKilling() {
        requireUnfinished();
        markModified();
        state = state.killing();
    }

    public void killUnfinishedTaskRuns() {
        requireState(State.Type.KILLING);
        if (unfinishedTaskRuns().isEmpty()) {
            return;
        }
        markModified();
        taskRuns.stream().filter(TaskRun::isUnfinished).forEach(TaskRun::kill);
    }

    public void finishKilling() {
        requireState(State.Type.KILLING);
        if (!unfinishedTaskRuns().isEmpty()) {
            throw new WorkflowException("Execution still has unfinished TaskRun: " + id);
        }
        markModified();
        state = state.killed();
    }

    public TaskRun requireTaskRun(String taskRunId) {
        return findTaskRun(taskRunId).orElseThrow(() -> new WorkflowException("TaskRun does not exist: " + taskRunId));
    }

    public Execution copy() {
        return new Execution(this);
    }

    private void markModified() {
        if (persisted && !modified) {
            lock();
            modified = true;
        }
    }

    private void validateRehydratedState() {
        HashSet<String> ids = new HashSet<>();
        HashSet<TaskOccurrence> occurrences = new HashSet<>();
        for (TaskRun taskRun : taskRuns) {
            if (!ids.add(taskRun.id())) {
                throw new IllegalArgumentException("Execution has duplicate TaskRun id: " + taskRun.id());
            }
            if (!occurrences.add(TaskOccurrence.from(taskRun))) {
                throw new IllegalArgumentException("Execution has duplicate TaskRun occurrence: " + taskRun.taskId());
            }
            taskRun.parentId().ifPresent(parentId -> {
                if (!ids.contains(parentId)) {
                    throw new IllegalArgumentException("TaskRun parent must precede child in Execution: " + taskRun.id());
                }
            });
        }
        if (state.is(State.Type.CREATED) && !taskRuns.isEmpty()) {
            throw new IllegalArgumentException("Created Execution must not have TaskRuns");
        }
        if (state.isTerminal() && !unfinishedTaskRuns().isEmpty()) {
            throw new IllegalArgumentException("Terminal Execution must not have unfinished TaskRuns");
        }
        if (state.is(State.Type.PAUSED) && (!activeTaskRuns().isEmpty() || pausedTaskRuns().isEmpty())) {
            throw new IllegalArgumentException("Paused Execution must be at a stable Pause TaskRun");
        }
        validateStateRoute();
    }

    private static final class TaskOccurrence {

        private final String taskId;
        private final String parentId;
        private final Integer iteration;

        private TaskOccurrence(String taskId, String parentId, Integer iteration) {
            this.taskId = taskId;
            this.parentId = parentId;
            this.iteration = iteration;
        }

        private static TaskOccurrence from(TaskRun taskRun) {
            return new TaskOccurrence(taskRun.taskId(), taskRun.parentId().orElse(null), taskRun.iteration().isPresent() ? taskRun.iteration().getAsInt() : null);
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            if (!(value instanceof TaskOccurrence other)) {
                return false;
            }
            return Objects.equals(taskId, other.taskId) && Objects.equals(parentId, other.parentId) && Objects.equals(iteration, other.iteration);
        }

        @Override
        public int hashCode() {
            return Objects.hash(taskId, parentId, iteration);
        }
    }

    private void validateStateRoute() {
        List<State.History> stateHistory = state.history();
        for (int index = 1; index < stateHistory.size(); index++) {
            State.Type source = stateHistory.get(index - 1).state();
            State.Type target = stateHistory.get(index).state();
            boolean valid = switch (source) {
                case CREATED -> target == State.Type.RUNNING || target == State.Type.KILLING;
                case RUNNING ->
                        target == State.Type.PAUSED || target == State.Type.SUCCESS || target == State.Type.WARNING || target == State.Type.FAILED || target == State.Type.KILLING;
                case PAUSED -> target == State.Type.RESTARTED || target == State.Type.KILLING;
                case RESTARTED -> target == State.Type.RUNNING || target == State.Type.KILLING;
                case KILLING -> target == State.Type.KILLED;
                case SUCCESS, WARNING, FAILED, KILLED -> false;
            };
            if (!valid) {
                throw new IllegalArgumentException("Invalid Execution state transition from " + source + " to " + target);
            }
        }
    }

    private void requireRunning() {
        requireState(State.Type.RUNNING);
    }

    private void requireState(State.Type expected) {
        if (!state.is(expected)) {
            throw new WorkflowException("Execution must be " + expected + " but was " + state + ": " + id);
        }
    }

    private void requireUnfinished() {
        if (state.isTerminal()) {
            throw new WorkflowException("Execution must be unfinished but was " + state + ": " + id);
        }
        if (state.is(State.Type.KILLING)) {
            throw new WorkflowException("Execution is already KILLING: " + id);
        }
    }

    private static void requireTaskRunState(TaskRun taskRun, State.Type expected) {
        if (!taskRun.state().is(expected)) {
            throw new WorkflowException("TaskRun " + taskRun.id() + " must be " + expected + " but was " + taskRun.state());
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

}
