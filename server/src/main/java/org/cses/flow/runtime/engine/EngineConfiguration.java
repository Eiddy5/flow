package org.cses.flow.runtime.engine;

import java.util.Objects;
import org.cses.flow.runtime.behavior.ActivityBehaviorRegistry;
import org.cses.flow.shared.IdGenerator;

public record EngineConfiguration(
        IdGenerator idGenerator,
        ActivityBehaviorRegistry behaviorRegistry) {

    public EngineConfiguration {
        Objects.requireNonNull(idGenerator, "idGenerator");
        Objects.requireNonNull(behaviorRegistry, "behaviorRegistry");
    }
}
