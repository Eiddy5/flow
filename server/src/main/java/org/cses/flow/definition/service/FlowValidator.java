package org.cses.flow.definition.service;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import org.cses.flow.definition.model.Edge;
import org.cses.flow.definition.model.Flow;
import org.cses.flow.definition.model.Node;
import org.cses.flow.definition.model.NodeType;

public final class FlowValidator {

    public void validateForDeployment(Flow flow) {
        long startCount = flow.nodes().stream()
                .filter(node -> node.type() == NodeType.START)
                .count();
        if (startCount != 1) {
            throw new IllegalArgumentException("Flow must contain exactly one START Node");
        }
        if (flow.nodes().stream().noneMatch(node -> node.type() == NodeType.END)) {
            throw new IllegalArgumentException("Flow must contain at least one END Node");
        }

        Set<String> nodeIds = new HashSet<>();
        for (Node node : flow.nodes()) {
            if (!nodeIds.add(node.id())) {
                throw new IllegalArgumentException("Duplicate Node id: " + node.id());
            }
        }
        for (Edge edge : flow.edges()) {
            if (!nodeIds.contains(edge.sourceId()) || !nodeIds.contains(edge.targetId())) {
                throw new IllegalArgumentException("Edge references a missing Node: " + edge.id());
            }
            if (edge.source() == null || edge.target() == null) {
                throw new IllegalArgumentException("Edge object relation is not assembled: " + edge.id());
            }
        }

        for (Node node : flow.nodes()) {
            if (node.type() == NodeType.START && !node.incoming().isEmpty()) {
                throw new IllegalArgumentException("START Node cannot have incoming Edge");
            }
            if (node.type() == NodeType.END && !node.outgoing().isEmpty()) {
                throw new IllegalArgumentException("END Node cannot have outgoing Edge");
            }
            if (node.type() != NodeType.END && node.outgoing().size() != 1) {
                throw new IllegalArgumentException(
                        node.type() + " Node must have exactly one outgoing Edge in VER-FLOW-001");
            }
            if (node.type() != NodeType.START && node.incoming().isEmpty()) {
                throw new IllegalArgumentException("Non-START Node must have an incoming Edge: " + node.id());
            }
        }

        assertAllNodesReachable(flow);
    }

    private void assertAllNodesReachable(Flow flow) {
        Set<String> reachable = new HashSet<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(flow.startNode());
        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            if (!reachable.add(node.id())) {
                continue;
            }
            node.outgoing().forEach(edge -> queue.addLast(edge.target()));
        }
        if (reachable.size() != flow.nodes().size()) {
            throw new IllegalArgumentException("Flow contains unreachable Nodes");
        }
    }
}
