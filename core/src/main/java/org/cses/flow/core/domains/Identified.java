package org.cses.flow.core.domains;

import org.cses.flow.core.exceptions.WorkflowException;

/**
 * Domain capability for an object with one stable string entity identity.
 */
public interface Identified {

    String id();

    default boolean identifiedBy(String candidate) {
        return id().equals(candidate);
    }

    default void requireIdentifier(String candidate) {
        if (!identifiedBy(candidate)) {
            throw new WorkflowException(
                "Domain identity mismatch: expected " + id()
                    + " but was " + candidate
            );
        }
    }
}
