package org.cses.flow.infrastructure.memory;

import java.util.UUID;
import org.cses.flow.shared.IdGenerator;

public final class InMemoryIdGenerator implements IdGenerator {

    @Override
    public String nextId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
