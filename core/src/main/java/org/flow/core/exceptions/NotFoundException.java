package org.flow.core.exceptions;

/**
 * General exception that can be throws when a Flow resource or entity is not found.
 */
public class NotFoundException extends FlowRuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new {@link NotFoundException} instance.
     */
    public NotFoundException() {
        super();
    }

    /**
     * Creates a new {@link NotFoundException} instance.
     *
     * @param message the error message.
     */
    public NotFoundException(final String message) {
        super(message);
    }
}
