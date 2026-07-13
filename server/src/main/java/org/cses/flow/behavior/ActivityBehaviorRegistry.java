package org.cses.flow.behavior;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.cses.flow.definition.model.NodeType;

public final class ActivityBehaviorRegistry {

    private final Map<NodeType, ActivityBehavior> behaviors;

    public ActivityBehaviorRegistry(Map<NodeType, ActivityBehavior> behaviors) {
        EnumMap<NodeType, ActivityBehavior> copy = new EnumMap<>(NodeType.class);
        copy.putAll(Objects.requireNonNull(behaviors, "behaviors"));
        this.behaviors = Map.copyOf(copy);
    }

    public ActivityBehavior get(NodeType nodeType) {
        ActivityBehavior behavior = behaviors.get(nodeType);
        if (behavior == null) {
            throw new IllegalArgumentException("No ActivityBehavior registered for " + nodeType);
        }
        return behavior;
    }
}
