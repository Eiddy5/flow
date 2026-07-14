package org.cses.flow.runtime.behavior;

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

    public ActivityBehavior get(NodeType type) {
        ActivityBehavior behavior = behaviors.get(type);
        if (behavior == null) {
            throw new IllegalStateException("No ActivityBehavior registered for " + type);
        }
        return behavior;
    }
}
