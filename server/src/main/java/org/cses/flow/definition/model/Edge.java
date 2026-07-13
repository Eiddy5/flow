package org.cses.flow.definition.model;

import java.util.Objects;

public final class Edge {

    private final String id;
    private final String sourceId;
    private final String targetId;
    private String flowId;
    private Node source;
    private Node target;

    public Edge(String id, String sourceId, String targetId) {
        this.id = Objects.requireNonNull(id, "id");
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.targetId = Objects.requireNonNull(targetId, "targetId");
    }

    void bind(String flowId, Node source, Node target) {
        this.flowId = Objects.requireNonNull(flowId, "flowId");
        this.source = Objects.requireNonNull(source, "source");
        this.target = Objects.requireNonNull(target, "target");
    }

    public String id() {
        return id;
    }

    public String flowId() {
        return flowId;
    }

    public String sourceId() {
        return sourceId;
    }

    public String targetId() {
        return targetId;
    }

    public Node source() {
        return source;
    }

    public Node target() {
        return target;
    }
}
