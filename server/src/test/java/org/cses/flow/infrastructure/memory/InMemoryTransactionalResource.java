package org.cses.flow.infrastructure.memory;

/**
 * A test repository whose state can be restored on command failure.
 */
public interface InMemoryTransactionalResource {

    Object snapshot();

    void restore(Object snapshot);
}
