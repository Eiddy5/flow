package org.cses.flow.core.domains.executions;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.BaseDomain;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.utils.RequiredUtil;
import org.paas.common.util.StringUtil;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.*;

/**
 * Aggregate root for one complete Flow start instance.
 */
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Execution extends BaseDomain {

    String flowKey;
    Long flowVersion;
    List<TaskRun> taskRuns;
    Generation generation;
    Origin origin;
    String parentTaskRunId;
    int inheritedTaskRunCount;
    State state;
    Map<String, Object> inputs;
    Long lock;

    /**
     * Creates a root snapshot with fresh lifecycle facts and the supplied identity.
     * @param id nonblank identity allocated once for this Execution
     * @param session trusted tenant and creator
     * @param flowKey exact Flow key
     * @param flowVersion positive bound Flow version
     * @param inputs start inputs, defensively copied
     * @throws IllegalArgumentException when required identities or version are invalid
     */
    private Execution(
            String id,
            Session<? extends User> session,
            String flowKey,
            long flowVersion,
            Map<String, ?> inputs
    ) {
        super(id, session);
        this.flowKey = requireText(flowKey, "Flow key");
        if (flowVersion < 1) {
            throw new IllegalArgumentException("Flow version must be positive");
        }
        this.flowVersion = flowVersion;
        this.taskRuns = new ArrayList<>();
        this.generation = Generation.empty();
        this.inputs = immutableMap(inputs);
        this.state = State.created();
        this.origin = Origin.create(null, id);
    }

    /**
     * 从持久化事实恢复完整运行，校验来源、节点和状态的一致性。
     * @param id 非空运行编号
     * @param companyId 非空所属租户
     * @param creator 原始创建人快照
     * @param createdAt 原始创建毫秒时间
     * @param flowKey 绑定的流程键
     * @param flowVersion 绑定的正整数流程版本
     * @param inputs 输入快照，复制保存
     * @param generation 运行迭代历史，复制保存
     * @param state 不可变状态历史
     * @param taskRuns 本运行自有节点，按序复制
     * @param origin 不可变的直接来源与根来源
     * @param inheritedTaskRuns 沿用的节点快照，按序复制
     * @param parentTaskRunId 子调用的精确父节点，非子调用为 null
     * @param lock 查询时版本；内存副本尚未保存时允许 null
     * @throws IllegalArgumentException 快照身份或历史不一致时抛出
     */
    private Execution(
            String id,
            String companyId,
            ActorRef creator,
            long createdAt,
            String flowKey,
            long flowVersion,
            Map<String, ?> inputs,
            Generation generation,
            State state,
            List<TaskRun> taskRuns,
            Origin origin,
            List<TaskRun> inheritedTaskRuns,
            String parentTaskRunId,
            Long lock
    ) {
        super(id, companyId, creator, createdAt);
        if (lock != null && lock < 0) throw new IllegalArgumentException("lock must not be negative");
        this.lock = lock;
        this.flowKey = requireText(flowKey, "Flow key");
        if (flowVersion < 1) {
            throw new IllegalArgumentException("Flow version must be positive");
        }
        this.flowVersion = flowVersion;
        this.inputs = immutableMap(inputs);
        this.generation = RequiredUtil.required(
                generation,
                "Execution generation"
        ).copy();
        this.state = RequiredUtil.required(state, "Execution state");
        this.origin = Objects.requireNonNull(origin, "Execution origin");
        this.parentTaskRunId = parentTaskRunId;
        this.taskRuns = new ArrayList<>();
        Objects.requireNonNull(inheritedTaskRuns, "Inherited TaskRuns").stream()
                .map(TaskRun::copy).forEach(this.taskRuns::add);
        this.inheritedTaskRunCount = this.taskRuns.size();
        if (taskRuns != null) {
            taskRuns.stream().map(TaskRun::copy).forEach(this.taskRuns::add);
        }
        validateRehydratedState();
    }

    public static Execution create(
            String requestedId,
            Session<? extends User> session,
            String flowKey,
            long flowVersion,
            Map<String, ?> inputs
    ) {
        String id = requestedId == null || requestedId.isBlank()
                ? StringUtil.newId()
                : requestedId;
        return new Execution(
                id,
                session,
                flowKey,
                flowVersion,
                inputs
        );
    }

    /**
     * 恢复已存储的完整运行，不生成新的运行事实。
     * @param id 非空运行编号
     * @param companyId 非空所属租户
     * @param creator 原始创建人快照
     * @param createdAt 原始创建毫秒时间
     * @param flowKey 绑定的流程键
     * @param flowVersion 绑定的正整数流程版本
     * @param inputs 输入快照，复制保存
     * @param generation 运行迭代历史，复制保存
     * @param state 不可变状态历史
     * @param taskRuns 本运行自有节点，按序复制
     * @param origin 不可变的直接来源与根来源
     * @param inheritedTaskRuns 沿用的节点快照，按序复制
     * @param parentTaskRunId 子调用的精确父节点，非子调用为 null
     * @param lock 数据库中非负的查询时版本
     * @return 已校验的独立运行快照
     * @throws IllegalArgumentException 快照身份或历史不一致时抛出
     */
    public static Execution rehydrate(
            String id, String companyId, ActorRef creator, long createdAt,
            String flowKey, long flowVersion, Map<String, ?> inputs,
            Generation generation, State state, List<TaskRun> taskRuns,
            Origin origin, List<TaskRun> inheritedTaskRuns, String parentTaskRunId, long lock
    ) {
        return new Execution(id, companyId, creator, createdAt, flowKey, flowVersion,
                inputs, generation, state, taskRuns, origin, inheritedTaskRuns, parentTaskRunId, lock);
    }

    /** @return 此快照的持久化版本；null 表示尚未入库，不参与业务状态判断 */
    public Long lock() {
        return lock;
    }

    /**
     * 由仓储在保存成功后回填版本，不改变领域事实；业务调用方不得自行推进。
     * @param version 数据库返回的非负版本，显式事务回滚后必须丢弃本对象并重读
     * @throws IllegalArgumentException 当版本为负数时抛出
     */
    public void lock(long version) {
        if (version < 0) throw new IllegalArgumentException("lock must not be negative");
        lock = version;
    }

    /** @return immutable direct-parent and root Execution relationship */
    public Origin origin() {
        return origin;
    }

    /** @return 发起本次子调用的父 TaskRun；根运行和退回派生运行为 null */
    public String parentTaskRunId() {
        return parentTaskRunId;
    }

    /**
     * 启动指定调用节点并创建独立子 Execution，保存来源与调用身份。
     * @param session 与父运行同租户的可信会话
     * @param taskRunId 父运行中尚未启动的精确调用节点
     * @param flowKey 已解析的子流程键
     * @param flowVersion 已解析的子流程版本
     * @param boundInputs 经 Task 和子 Flow 校验的实际输入
     * @return 尚未持久化的子 Execution；调用方必须原子保存父子快照
     * @throws IllegalArgumentException 会话租户或目标定义非法时抛出
     * @throws WorkflowException 父运行或调用节点不能启动时抛出
     */
    public Execution startSubFlow(Session<? extends User> session, String taskRunId,
            String flowKey, long flowVersion, Map<String, ?> boundInputs) {
        requireRunning();
        if (!companyId().equals(session.getCompanyId())) {
            throw new IllegalArgumentException("SubFlow must remain in the parent tenant");
        }
        TaskRun caller = requireTaskRun(taskRunId);
        requireTaskRunState(caller, State.Type.CREATED);
        Execution child = create(null, session, flowKey, flowVersion, boundInputs);
        child.origin = Origin.create(id(), origin.originId());
        child.parentTaskRunId = caller.id();
        caller.start(boundInputs);
        return child;
    }

    /** @return inherited run snapshots in their original order */
    public List<TaskRun> inheritedTaskRuns() {
        return List.copyOf(taskRuns.subList(0, inheritedTaskRunCount));
    }

    /** @return only the run occurrences first created by this Execution */
    public List<TaskRun> ownTaskRuns() {
        return List.copyOf(taskRuns.subList(inheritedTaskRunCount, taskRuns.size()));
    }

    public String flowKey() {
        return flowKey;
    }

    public long flowVersion() {
        return flowVersion;
    }

    /**
     * Returns the normalized values supplied for the exact Flow version.
     */
    public Map<String, Object> inputs() {
        return inputs;
    }

    public State state() {
        return state;
    }

    public Generation generation() {
        return generation.copy();
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
     * Reads the latest effective occurrence for the given parent and iteration.
     *
     * @param taskId nonblank definition ID
     * @param parentTaskRunId exact parent occurrence ID, or null for a root
     * @param iteration positive loop iteration, or null outside a loop
     * @return the owned occurrence, or empty when absent or invalidated
     */
    public Optional<TaskRun> taskRunForOccurrence(String taskId, String parentTaskRunId, Integer iteration) {
        String normalizedTaskId = requireText(taskId, "Task id");
        List<TaskRun> matches = effectiveTaskRuns().stream()
                .filter(taskRun -> taskRun.taskId().equals(normalizedTaskId))
                .filter(taskRun -> Objects.equals(
                        taskRun.parentId().orElse(null),
                        parentTaskRunId
                ))
                .filter(taskRun -> Objects.equals(
                        taskRun.iteration().isPresent()
                                ? taskRun.iteration().getAsInt()
                                : null,
                        iteration
                ))
                .toList();
        return matches.isEmpty()
                ? Optional.empty()
                : Optional.of(matches.getLast());
    }

    /**
     * Reads an effective occurrence in one exact execution generation.
     *
     * @param taskId nonblank definition ID
     * @param parentTaskRunId exact parent occurrence ID, or null for a root
     * @param iteration positive loop iteration, or null outside a loop
     * @param executionGenerationVersion exact execution generation, or null for an original occurrence
     * @return the owned occurrence, or empty when absent or invalidated
     */
    public Optional<TaskRun> taskRunForOccurrence(
            String taskId,
            String parentTaskRunId,
            Integer iteration,
            Integer executionGenerationVersion
    ) {
        String normalizedTaskId = requireText(taskId, "Task id");
        return effectiveTaskRuns().stream()
                .filter(taskRun -> taskRun.taskId().equals(normalizedTaskId))
                .filter(taskRun -> Objects.equals(
                        taskRun.parentId().orElse(null),
                        parentTaskRunId
                ))
                .filter(taskRun -> Objects.equals(
                        taskRun.iteration().isPresent()
                                ? taskRun.iteration().getAsInt()
                                : null,
                        iteration
                ))
                .filter(taskRun -> Objects.equals(
                        taskRun.executionGenerationVersion().isPresent()
                                ? taskRun.executionGenerationVersion().getAsInt()
                                : null,
                        executionGenerationVersion
                ))
                .findFirst();
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
        state = state.running();
        taskRuns.addAll(accepted);
    }

    public TaskRun createTaskRun(String taskId, String parentTaskRunId, Map<String, ?> inputs) {
        TaskRun taskRun = TaskRun.create(taskId, parentTaskRunId, inputs);
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
            taskRun.parentId().ifPresent(parentTaskRunId -> {
                if (!taskRunIds.contains(parentTaskRunId)) {
                    throw new WorkflowException("Parent TaskRun does not exist: " + parentTaskRunId);
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
        taskRun.start();
    }

    public void succeedTaskRun(String taskRunId, Map<String, ?> outputs) {
        requireRunning();
        TaskRun taskRun = requireTaskRun(taskRunId);
        requireTaskRunState(taskRun, State.Type.RUNNING);
        taskRun.succeed(outputs);
    }

    /**
     * Records an orchestration decision that was evaluated but not selected.
     */
    public void skipTaskRun(String taskRunId) {
        requireRunning();
        TaskRun taskRun = requireTaskRun(taskRunId);
        requireTaskRunState(taskRun, State.Type.RUNNING);
        taskRun.skip();
    }

    public void warnTaskRun(String taskRunId, Map<String, ?> outputs) {
        requireRunning();
        TaskRun taskRun = requireTaskRun(taskRunId);
        requireTaskRunState(taskRun, State.Type.RUNNING);
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
        pausing.forEach(TaskRun::pause);
    }

    /**
     * Reports whether the execution can accept completion of a waiting branch.
     * @return true while running, restarting or paused; false during cancellation or after termination
     */
    public boolean canResumeTaskRun() {
        return state.is(State.Type.PAUSED) || state.is(State.Type.RUNNING) || state.is(State.Type.RESTARTED);
    }

    /**
     * Recognizes a started occurrence that has not reached its first pause yet.
     * The service additionally checks that the bound task definition is a Pause.
     * @param taskRunId exact occurrence whose pre-pause action is running
     * @return whether a deferred resume may wait for the first paused state
     * @throws WorkflowException when the occurrence does not exist
     */
    public boolean isAwaitingPause(String taskRunId) {
        TaskRun taskRun = requireTaskRun(taskRunId);
        return canResumeTaskRun() && taskRun.state().is(State.Type.RUNNING)
                && taskRun.state().history().stream().noneMatch(change -> change.state() == State.Type.PAUSED);
    }

    /**
     * Resumes the exact waiting TaskRun without requiring sibling branches to stop.
     * @param taskRunId identity of the waiting TaskRun
     * @param outputs validated external outputs for this occurrence
     * @throws WorkflowException when the execution or target cannot resume
     */
    public void resumeTaskRun(String taskRunId, Map<String, ?> outputs) {
        if (!canResumeTaskRun()) {
            throw new WorkflowException("Execution cannot resume a TaskRun: " + id());
        }
        TaskRun taskRun = requireTaskRun(taskRunId);
        if (effectiveTaskRuns().stream().noneMatch(run -> run.identifiedBy(taskRunId))) {
            throw new WorkflowException("TaskRun no longer belongs to the effective execution path: " + taskRunId);
        }
        requireTaskRunState(taskRun, State.Type.PAUSED);
        taskRun.resume(outputs);
        resumePausedAncestors(taskRun);
        completeRewindWhenSourceResumes(taskRun);
        if (state.is(State.Type.PAUSED)) {
            state = state.restarted();
        }
    }

    /**
     * Derives a running Execution from this snapshot and stops this Execution after validation.
     * Unaffected runs retain their identities and progress in isolated inherited snapshots.
     * @param executionId preallocated identity of the new Execution
     * @param session trusted tenant and actor creating the new Execution
     * @param sourceTaskRunId current paused source occurrence
     * @param targetTaskRunId completed target occurrence to execute again
     * @param reason nonblank replay reason
     * @param affectedTaskRunIds exact validated affected path, including both endpoints
     * @return new running Execution; this source is now KILLED
     * @throws WorkflowException when this snapshot cannot replay
     * @throws IllegalArgumentException when identities or the session do not match
     */
    public Execution replay(String executionId, Session<? extends User> session,
            String sourceTaskRunId, String targetTaskRunId, String reason,
            List<String> affectedTaskRunIds) {
        if (identifiedBy(executionId) || !companyId().equals(session.getCompanyId())) {
            throw new IllegalArgumentException("Replay requires a new identity in the same tenant");
        }
        if (!canResumeTaskRun()) {
            throw new WorkflowException("Execution cannot replay: " + id());
        }
        Execution next = create(requireText(executionId, "Replay Execution id"), session,
                flowKey, flowVersion, inputs);
        next.origin = Origin.create(id(), origin.originId());
        // ponytail: a full snapshot per replay; share immutable history blocks if long chains make storage material.
        next.taskRuns = taskRuns.stream().map(TaskRun::copy)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        next.inheritedTaskRunCount = next.taskRuns.size();
        next.generation = generation.copy();
        next.start();
        next.rewindTaskRun(sourceTaskRunId, targetTaskRunId, reason, affectedTaskRunIds);
        next.validateRehydratedState();
        beginKilling();
        killUnfinishedTaskRuns();
        finishKilling();
        return next;
    }

    /**
     * Invalidates the planned occurrences and reopens only their shared ancestors.
     *
     * @param sourceTaskRunId effective paused source occurrence ID
     * @param targetTaskRunId effective completed preceding occurrence ID
     * @param reason nonblank rewind reason, trimmed before storage
     * @param affectedTaskRunIds unique effective IDs including source and target, defensively copied into Generation
     * @throws WorkflowException when the plan is stale or the execution cannot rewind
     */
    private void rewindTaskRun(
            String sourceTaskRunId, String targetTaskRunId, String reason,
            List<String> affectedTaskRunIds
    ) {
        if (!canResumeTaskRun()) {
            throw new WorkflowException("Execution cannot rewind: " + id());
        }
        String normalizedReason = requireText(reason, "Rewind reason");
        TaskRun source = requireTaskRun(sourceTaskRunId);
        TaskRun target = requireTaskRun(targetTaskRunId);
        requireTaskRunState(source, State.Type.PAUSED);
        if (!target.state().is(State.Type.SUCCESS) && !target.state().is(State.Type.WARNING)) {
            throw new WorkflowException("Rewind target TaskRun must be completed: " + target.id());
        }
        Set<String> affected = new HashSet<>(affectedTaskRunIds);
        Set<String> effective = effectiveTaskRuns().stream().map(TaskRun::id)
                .collect(java.util.stream.Collectors.toSet());
        if (!affected.contains(source.id()) || !affected.contains(target.id())
                || affected.size() != affectedTaskRunIds.size() || !effective.containsAll(affected)
                || indexOfTaskRun(target.id()) >= indexOfTaskRun(source.id())) {
            throw new WorkflowException("Rewind requires an effective source, preceding target and affected path");
        }
        Set<String> ancestors = new HashSet<>();
        for (String id : affected) {
            TaskRun run = requireTaskRun(id);
            Optional<String> parent = run.parentId();
            while (parent.isPresent()) {
                String parentId = parent.orElseThrow();
                if (!affected.contains(parentId)) ancestors.add(parentId);
                parent = requireTaskRun(parentId).parentId();
            }
        }
        if (generation.active()) {
            generation.advance(source.id(), target.id(), normalizedReason, affectedTaskRunIds);
        } else {
            generation.start(source.id(), target.id(), normalizedReason, affectedTaskRunIds);
        }
        affected.stream().map(this::requireTaskRun).filter(TaskRun::isUnfinished).forEach(TaskRun::kill);
        ancestors.stream().map(this::requireTaskRun).forEach(TaskRun::reopenForRewind);
        if (state.is(State.Type.PAUSED)) state = state.restarted();
    }

    public void startTaskRunGeneration(String taskRunId, String reason) {
        TaskRun taskRun = requireTaskRun(taskRunId);
        requireTaskRunState(taskRun, State.Type.RUNNING);
        taskRun.startGeneration(reason);
    }

    public void advanceTaskRunGeneration(String taskRunId, String reason) {
        TaskRun taskRun = requireTaskRun(taskRunId);
        requireTaskRunState(taskRun, State.Type.RUNNING);
        taskRun.advanceGeneration(reason);
    }

    public void completeTaskRunGeneration(String taskRunId) {
        TaskRun taskRun = requireTaskRun(taskRunId);
        requireTaskRunState(taskRun, State.Type.RUNNING);
        taskRun.completeGeneration();
    }

    /**
     * Excludes all occurrences invalidated by current or archived rewind generations.
     *
     * @return inherited and owned TaskRuns still on the effective path, in history order
     */
    public List<TaskRun> effectiveTaskRuns() {
        Set<String> invalidated = new HashSet<>();
        for (Generation.Current version : executionGenerations()) {
            invalidated.addAll(version.affectedTaskRunIds());
        }
        return taskRuns.stream().filter(run -> !invalidated.contains(run.id())).toList();
    }

    /**
     * Finds the latest generation invalidating this exact occurrence before scheduling its replacement.
     *
     * @param taskId definition ID to locate
     * @param parentId exact parent occurrence ID, or null for a root
     * @param iteration loop iteration, or null outside a loop
     * @param inherited parent execution generation, or null for an original parent
     * @return the newest relevant version, or null when no generation applies
     */
    public Integer replayGenerationVersion(String taskId, String parentId, Integer iteration, Integer inherited) {
        Integer version = inherited;
        for (Generation.Current current : executionGenerations()) {
            for (String id : current.affectedTaskRunIds()) {
                TaskRun run = requireTaskRun(id);
                if (run.taskId().equals(taskId) && Objects.equals(run.parentId().orElse(null), parentId)
                        && Objects.equals(run.iteration().isPresent() ? run.iteration().getAsInt() : null, iteration)) {
                    version = version == null ? current.version() : Math.max(version, current.version());
                }
            }
        }
        return version;
    }

    /**
     * Combines archived and active generation records for effective-path reads.
     *
     * @return a new ordered list sharing immutable generation records
     */
    private List<Generation.Current> executionGenerations() {
        List<Generation.Current> versions = new ArrayList<>(generation.history().currents());
        generation.current().ifPresent(versions::add);
        return versions;
    }

    private void completeRewindWhenSourceResumes(TaskRun resumed) {
        Optional<Generation.Current> active = generation.current();
        if (active.isEmpty()) {
            return;
        }
        TaskRun originalSource = requireTaskRun(
                active.orElseThrow().sourceTaskRunId().orElseThrow()
        );
        if (originalSource.taskId().equals(resumed.taskId())) {
            generation.complete();
        }
    }

    private void resumePausedAncestors(TaskRun taskRun) {
        Optional<String> parentTaskRunId = taskRun.parentId();
        while (parentTaskRunId.isPresent()) {
            TaskRun parent = requireTaskRun(parentTaskRunId.orElseThrow());
            if (parent.state().is(State.Type.PAUSED)) {
                parent.resume(parent.outputs());
            }
            parentTaskRunId = parent.parentId();
        }
    }

    public Execution restart() {
        requireState(State.Type.RESTARTED);
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
        completeActiveGenerations();
        taskRun.fail(error);
        taskRuns.stream().filter(other -> other != taskRun).filter(TaskRun::isUnfinished).forEach(TaskRun::kill);
        state = state.failed();
    }

    public void succeed() {
        requireRunning();
        if (!unfinishedTaskRuns().isEmpty()) {
            throw new WorkflowException(
                    "Execution has unfinished TaskRun: " + id()
            );
        }
        state = state.success();
    }

    public void warn() {
        requireRunning();
        if (!unfinishedTaskRuns().isEmpty()) {
            throw new WorkflowException(
                    "Execution has unfinished TaskRun: " + id()
            );
        }
        if (effectiveTaskRuns().stream().noneMatch(taskRun ->
                taskRun.state().is(State.Type.WARNING)
        )) {
            throw new WorkflowException(
                    "Execution cannot finish with WARNING without a warning "
                            + "TaskRun: " + id()
            );
        }
        state = state.warning();
    }

    public void pause() {
        requireRunning();
        if (!activeTaskRuns().isEmpty() || pausedTaskRuns().isEmpty()) {
            throw new WorkflowException(
                    "Execution can pause only at a stable Pause TaskRun: "
                            + id()
            );
        }
        state = state.paused();
    }

    public void beginKilling() {
        requireUnfinished();
        completeActiveGenerations();
        state = state.killing();
    }

    public void killUnfinishedTaskRuns() {
        requireState(State.Type.KILLING);
        if (unfinishedTaskRuns().isEmpty()) {
            return;
        }
        taskRuns.stream().filter(TaskRun::isUnfinished).forEach(TaskRun::kill);
    }

    public void finishKilling() {
        requireState(State.Type.KILLING);
        if (!unfinishedTaskRuns().isEmpty()) {
            throw new WorkflowException(
                    "Execution still has unfinished TaskRun: " + id()
            );
        }
        state = state.killed();
    }

    public TaskRun requireTaskRun(String taskRunId) {
        return findTaskRun(taskRunId).orElseThrow(() -> new WorkflowException("TaskRun does not exist: " + taskRunId));
    }

    /** @return 保留身份、来源、自有与继承快照及当前 lock 的独立副本 */
    public Execution copy() {
        return new Execution(
                id(),
                companyId(),
                creator(),
                createdAt(),
                flowKey,
                flowVersion,
                inputs,
                generation,
                state,
                ownTaskRuns(),
                origin,
                inheritedTaskRuns(),
                parentTaskRunId,
                lock
        );
    }

    /**
     * 校验当前快照的来源身份、继承边界及运行历史，不改变快照。
     * @throws IllegalArgumentException 身份、来源或历史不一致时抛出
     */
    private void validateRehydratedState() {
        if (parentTaskRunId != null && (parentTaskRunId.isBlank() || origin.parentId() == null
                || inheritedTaskRunCount != 0)) {
            throw new IllegalArgumentException("SubFlow requires a caller and no inherited TaskRuns");
        }
        if (origin.parentId() == null) {
            if (!id().equals(origin.originId()) || inheritedTaskRunCount != 0) {
                throw new IllegalArgumentException("Root Execution must reference itself without inherited runs");
            }
        } else if (id().equals(origin.parentId()) || id().equals(origin.originId())) {
            throw new IllegalArgumentException("Derived Execution must have distinct parent and root identities");
        }
        HashSet<String> ids = new HashSet<>();
        HashSet<TaskOccurrence> occurrences = new HashSet<>();
        for (TaskRun taskRun : taskRuns) {
            if (!ids.add(taskRun.id())) {
                throw new IllegalArgumentException("Execution has duplicate TaskRun id: " + taskRun.id());
            }
            if (!occurrences.add(TaskOccurrence.from(taskRun))) {
                throw new IllegalArgumentException("Execution has duplicate TaskRun occurrence: " + taskRun.taskId());
            }
            taskRun.parentId().ifPresent(parentTaskRunId -> {
                if (!ids.contains(parentTaskRunId)) {
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
        if (state.isTerminal() && (generation.active()
                || taskRuns.stream().anyMatch(taskRun ->
                        taskRun.generation().active()
                ))) {
            throw new IllegalArgumentException(
                    "Terminal Execution must not have an active Generation"
            );
        }
        if (state.is(State.Type.PAUSED) && (!activeTaskRuns().isEmpty() || pausedTaskRuns().isEmpty())) {
            throw new IllegalArgumentException("Paused Execution must be at a stable Pause TaskRun");
        }
        validateExecutionGeneration();
        validateStateRoute();
    }

    /**
     * Validates persisted generation references, affected paths and source-target order.
     *
     * @throws IllegalArgumentException when generation coordinates contradict the snapshot
     */
    private void validateExecutionGeneration() {
        List<Generation.Current> currents = new ArrayList<>(
                generation.history().currents()
        );
        generation.current().ifPresent(currents::add);
        for (Generation.Current current : currents) {
            String sourceId = current.sourceTaskRunId().orElseThrow(() ->
                    new IllegalArgumentException(
                            "Execution Generation requires a source TaskRun"
                    )
            );
            String targetId = current.targetTaskRunId().orElseThrow(() ->
                    new IllegalArgumentException(
                            "Execution Generation requires a target TaskRun"
                    )
            );
            if (!current.affectedTaskRunIds().contains(sourceId) || !current.affectedTaskRunIds().contains(targetId)) {
                throw new IllegalArgumentException("Execution Generation affected path requires source and target");
            }
            current.affectedTaskRunIds().forEach(this::requireTaskRun);
            if (indexOfTaskRun(targetId) >= indexOfTaskRun(sourceId)) {
                throw new IllegalArgumentException(
                        "Execution Generation target must precede its source"
                );
            }
        }
    }

    private static class TaskOccurrence {

        private String taskId;
        private String parentTaskRunId;
        private Integer iteration;
        private Integer executionGenerationVersion;

        private TaskOccurrence(
                String taskId,
                String parentTaskRunId,
                Integer iteration,
                Integer executionGenerationVersion
        ) {
            this.taskId = taskId;
            this.parentTaskRunId = parentTaskRunId;
            this.iteration = iteration;
            this.executionGenerationVersion = executionGenerationVersion;
        }

        private static TaskOccurrence from(TaskRun taskRun) {
            return new TaskOccurrence(
                    taskRun.taskId(),
                    taskRun.parentId().orElse(null),
                    taskRun.iteration().isPresent()
                            ? taskRun.iteration().getAsInt()
                            : null,
                    taskRun.executionGenerationVersion().isPresent()
                            ? taskRun.executionGenerationVersion().getAsInt()
                            : null
            );
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            if (!(value instanceof TaskOccurrence other)) {
                return false;
            }
            return Objects.equals(taskId, other.taskId)
                    && Objects.equals(parentTaskRunId, other.parentTaskRunId)
                    && Objects.equals(iteration, other.iteration)
                    && Objects.equals(
                            executionGenerationVersion,
                            other.executionGenerationVersion
                    );
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    taskId,
                    parentTaskRunId,
                    iteration,
                    executionGenerationVersion
            );
        }
    }

    private int indexOfTaskRun(String taskRunId) {
        TaskRun taskRun = requireTaskRun(taskRunId);
        return taskRuns.indexOf(taskRun);
    }

    private void completeActiveGenerations() {
        if (generation.active()) {
            generation.complete();
        }
        taskRuns.forEach(TaskRun::completeGenerationIfActive);
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
                case SUCCESS, SKIPPED, WARNING, FAILED, KILLED -> false;
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
            throw new WorkflowException(
                    "Execution must be " + expected + " but was " + state
                            + ": " + id()
            );
        }
    }

    private void requireUnfinished() {
        if (state.isTerminal()) {
            throw new WorkflowException(
                    "Execution must be unfinished but was " + state
                            + ": " + id()
            );
        }
        if (state.is(State.Type.KILLING)) {
            throw new WorkflowException(
                    "Execution is already KILLING: " + id()
            );
        }
    }

    private static void requireTaskRunState(TaskRun taskRun, State.Type expected) {
        if (!taskRun.state().is(expected)) {
            throw new WorkflowException("TaskRun " + taskRun.id() + " must be " + expected + " but was " + taskRun.state());
        }
    }

    private static String requireText(String value, String field) {
        return RequiredUtil.required(value, field + " must not be blank")
                .trim();
    }

    private static Map<String, Object> immutableMap(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(
                requireText(key, "Execution input key"),
                immutableValue(Objects.requireNonNull(
                        value,
                        "Execution input value"
                ))
        ));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(
                    requireText(String.valueOf(key), "Execution input key"),
                    immutableValue(Objects.requireNonNull(
                            nested,
                            "Execution input value"
                    ))
            ));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(item -> immutableValue(Objects.requireNonNull(
                            item,
                            "Execution input value"
                    )))
                    .toList();
        }
        return value;
    }

}
