package org.cses.flow.runtime.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.cses.flow.definition.model.Node;
import org.cses.flow.definition.model.NodeType;

public final class Activity {

    private final String id;
    private final String processId;
    private final String executorId;
    private final String nodeId;
    private final NodeType nodeType;
    private final Instant startedAt;
    private ActivityState state;
    private Map<String, Object> outputVariables = Map.of();
    private Instant endedAt;

    public Activity(String id, String processId, String executorId, Node node) {
        this.id = Objects.requireNonNull(id, "id");
        this.processId = Objects.requireNonNull(processId, "processId");
        this.executorId = Objects.requireNonNull(executorId, "executorId");
        this.nodeId = Objects.requireNonNull(node, "node").id();
        this.nodeType = node.type();
        this.state = ActivityState.RUNNING;
        this.startedAt = Instant.now();
    }

    private Activity(Activity source) {
        this.id = source.id;
        this.processId = source.processId;
        this.executorId = source.executorId;
        this.nodeId = source.nodeId;
        this.nodeType = source.nodeType;
        this.state = source.state;
        this.outputVariables = Map.copyOf(source.outputVariables);
        this.startedAt = source.startedAt;
        this.endedAt = source.endedAt;
    }

    public Activity copy() {
        return new Activity(this);
    }

    public void complete(Map<String, Object> outputVariables) {
        if (state != ActivityState.RUNNING) {
            throw new IllegalStateException("Only running Activity can complete: " + id);
        }
        this.outputVariables = Map.copyOf(new LinkedHashMap<>(outputVariables));
        this.state = ActivityState.COMPLETED;
        this.endedAt = Instant.now();
    }

    public String id() {
        return id;
    }

    public String processId() {
        return processId;
    }

    public String executorId() {
        return executorId;
    }

    public String nodeId() {
        return nodeId;
    }

    public NodeType nodeType() {
        return nodeType;
    }

    public ActivityState state() {
        return state;
    }

    public Map<String, Object> outputVariables() {
        return outputVariables;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant endedAt() {
        return endedAt;
    }
}
