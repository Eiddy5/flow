package org.cses.flow.core.domains;

import org.cses.flow.core.exceptions.WorkflowException;

/**
 * Domain capability for an entity with one stable technical identifier.
 */
public interface Identified {

    String identifier();

    default boolean identifiedBy(String identifier) {
        return identifier != null
            && !identifier.isBlank()
            && identifier().equals(identifier.trim());
    }

    default void requireIdentifier(String identifier) {
        if (!identifiedBy(identifier)) {
            throw new WorkflowException(
                "Domain identity mismatch: expected " + identifier()
                    + " but was " + identifier
            );
        }
    }
}
