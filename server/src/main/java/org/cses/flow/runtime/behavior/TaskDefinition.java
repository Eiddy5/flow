package org.cses.flow.runtime.behavior;

import java.util.Objects;

public record TaskDefinition(String name) {

    public TaskDefinition {
        Objects.requireNonNull(name, "name");
    }
}
