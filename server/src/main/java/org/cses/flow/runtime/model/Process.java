package org.cses.flow.runtime.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.cses.flow.definition.model.Flow;
import org.cses.flow.definition.model.Node;

public final class Process {

    private final String id;
    private final String flowId;
    private final String flowKey;
    private final long flowVersion;
    private final Instant startedAt;
    private final Map<String, Object> variables;
    private final Map<String, Executor> executors = new LinkedHashMap<>();
    private ProcessState state;
    private String rootExecutorId;
    private Instant endedAt;

    public Process(String id, Flow flow, Map<String, Object> variables) {
        this.id = Objects.requireNonNull(id, "id");
        this.flowId = Objects.requireNonNull(flow, "flow").id();
        this.flowKey = flow.key();
        this.flowVersion = Objects.requireNonNull(flow.version(), "flow.version");
        this.variables = new LinkedHashMap<>(Objects.requireNonNull(variables, "variables"));
        this.state = ProcessState.RUNNING;
        this.startedAt = Instant.now();
    }

    private Process(Process source) {
        this.id = source.id;
        this.flowId = source.flowId;
        this.flowKey = source.flowKey;
        this.flowVersion = source.flowVersion;
        this.startedAt = source.startedAt;
        this.variables = new LinkedHashMap<>(source.variables);
        source.executors.forEach((id, executor) -> executors.put(id, executor.copy()));
        this.state = source.state;
        this.rootExecutorId = source.rootExecutorId;
        this.endedAt = source.endedAt;
    }

    public Process copy() {
        return new Process(this);
    }

    public Executor createRootExecutor(String executorId, Node startNode) {
        if (rootExecutorId != null) {
            throw new IllegalStateException("Process already has a root Executor: " + id);
        }
        Executor executor = new Executor(executorId, id, null, startNode);
        executors.put(executor.id(), executor);
        rootExecutorId = executor.id();
        return executor;
    }

    public Executor executor(String executorId) {
        Executor executor = executors.get(executorId);
        if (executor == null) {
            throw new IllegalArgumentException("Executor not found in Process: " + executorId);
        }
        return executor;
    }

    public Executor rootExecutor() {
        if (rootExecutorId == null) {
            throw new IllegalStateException("Process has no root Executor: " + id);
        }
        return executor(rootExecutorId);
    }

    public void complete() {
        if (state != ProcessState.RUNNING) {
            throw new IllegalStateException("Only running Process can complete: " + id);
        }
        state = ProcessState.COMPLETED;
        endedAt = Instant.now();
    }

    public void completeIfPossible() {
        if (executors.isEmpty() || executors.values().stream()
                .anyMatch(executor -> executor.state() != ExecutorState.COMPLETED)) {
            return;
        }
        complete();
    }

    public String id() {
        return id;
    }

    public String flowId() {
        return flowId;
    }

    public String flowKey() {
        return flowKey;
    }

    public long flowVersion() {
        return flowVersion;
    }

    public ProcessState state() {
        return state;
    }

    public Map<String, Object> variables() {
        return Map.copyOf(variables);
    }

    public List<Executor> executors() {
        return List.copyOf(executors.values());
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant endedAt() {
        return endedAt;
    }

}
