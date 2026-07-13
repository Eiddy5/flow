package org.cses.flow.definition.command;

import java.util.List;
import java.util.Objects;
import org.cses.flow.definition.model.Edge;
import org.cses.flow.definition.model.Node;

public record CreateFlowCommand(
        String key,
        String name,
        List<Node> nodes,
        List<Edge> edges) {

    public CreateFlowCommand {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(name, "name");
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
    }
}
