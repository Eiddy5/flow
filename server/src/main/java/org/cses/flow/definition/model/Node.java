package org.cses.flow.definition.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class Node {

    private final String id;
    private final String name;
    private final NodeType type;
    private final Map<String, Object> config;
    private final List<Edge> incoming = new ArrayList<>();
    private final List<Edge> outgoing = new ArrayList<>();
    private String flowId;

    public Node(String id, String name, NodeType type, Map<String, Object> config) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.config = ImmutableValue.copyMap(Objects.requireNonNull(config, "config"));
    }

    void bindToFlow(String flowId) {
        if (this.flowId != null && !this.flowId.equals(flowId)) {
            throw new IllegalStateException("Node already belongs to another Flow: " + id);
        }
        this.flowId = flowId;
    }

    void addIncoming(Edge edge) {
        incoming.add(edge);
    }

    void addOutgoing(Edge edge) {
        outgoing.add(edge);
    }

    public String id() {
        return id;
    }

    public String flowId() {
        return flowId;
    }

    public String name() {
        return name;
    }

    public NodeType type() {
        return type;
    }

    public Map<String, Object> config() {
        return config;
    }

    public List<Edge> incoming() {
        return Collections.unmodifiableList(incoming);
    }

    public List<Edge> outgoing() {
        return Collections.unmodifiableList(outgoing);
    }
}
