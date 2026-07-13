package org.cses.flow.definition.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class Flow {

    private final String id;
    private final String key;
    private final String name;
    private final List<Node> nodes;
    private final List<Edge> edges;
    private final Instant createdAt;
    private FlowState state;
    private Long version;
    private Instant deployedAt;

    private Flow(String id, String key, String name, List<Node> nodes, List<Edge> edges) {
        this.id = Objects.requireNonNull(id, "id");
        this.key = Objects.requireNonNull(key, "key");
        this.name = Objects.requireNonNull(name, "name");
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
        this.state = FlowState.DRAFT;
        this.createdAt = Instant.now();
        assembleObjectRelations();
    }

    public static Flow draft(
            String id,
            String key,
            String name,
            List<Node> nodes,
            List<Edge> edges) {
        return new Flow(id, key, name, nodes, edges);
    }

    private void assembleObjectRelations() {
        Map<String, Node> nodesById = new LinkedHashMap<>();
        for (Node node : nodes) {
            if (nodesById.put(node.id(), node) != null) {
                throw new IllegalArgumentException("Duplicate Node id: " + node.id());
            }
            node.bindToFlow(id);
        }

        for (Edge edge : edges) {
            Node source = nodesById.get(edge.sourceId());
            Node target = nodesById.get(edge.targetId());
            if (source == null || target == null) {
                continue;
            }
            edge.bind(id, source, target);
            source.addOutgoing(edge);
            target.addIncoming(edge);
        }
    }

    public void markDeployed(long version) {
        if (state != FlowState.DRAFT) {
            throw new IllegalStateException("Only draft Flow can be deployed: " + id);
        }
        if (version < 1) {
            throw new IllegalArgumentException("Flow version must be positive");
        }
        this.version = version;
        this.state = FlowState.DEPLOYED;
        this.deployedAt = Instant.now();
    }

    public Node startNode() {
        return nodes.stream()
                .filter(node -> node.type() == NodeType.START)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Flow has no START Node: " + id));
    }

    public Node node(String nodeId) {
        return nodes.stream()
                .filter(node -> node.id().equals(nodeId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Node not found in Flow: " + nodeId));
    }

    public String id() {
        return id;
    }

    public String key() {
        return key;
    }

    public String name() {
        return name;
    }

    public FlowState state() {
        return state;
    }

    public Long version() {
        return version;
    }

    public List<Node> nodes() {
        return nodes;
    }

    public List<Edge> edges() {
        return edges;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant deployedAt() {
        return deployedAt;
    }
}
