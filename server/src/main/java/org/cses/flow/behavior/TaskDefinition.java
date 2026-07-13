package org.cses.flow.behavior;

import java.util.Objects;

public record TaskDefinition(String name) {

    public TaskDefinition {
        Objects.requireNonNull(name, "name");
    }
}
