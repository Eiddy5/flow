package org.flow.core.exceptions;

import java.io.Serial;

/**
 * The top-level {@link FlowException}..
 */
public class FlowException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    public FlowException() {
    }

    public FlowException(String message) {
        super(message);
    }

    public FlowException(String message, Throwable cause) {
        super(message, cause);
    }

    public FlowException(Throwable cause) {
        super(cause);
    }

}
