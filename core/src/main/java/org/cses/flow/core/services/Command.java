package org.cses.flow.core.services;

/**
 * Describes a state-changing request whose result type is {@code R}.
 *
 * @param <R> command result type
 */
public interface Command<R> {

    /**
     * Validates fields carried by this command.
     *
     * <p>Validation must not access repositories or change persistent state.</p>
     */
    default void validate() {
    }
}
