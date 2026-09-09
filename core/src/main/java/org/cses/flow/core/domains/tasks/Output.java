package org.cses.flow.core.domains.tasks;

import org.cses.flow.core.domains.flows.State;

import java.util.Optional;

public interface Output {
    default Optional<State.Type> state() {
        return Optional.empty();
    }
}
