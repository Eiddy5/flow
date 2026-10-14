package org.flow.core.exceptions;

import java.io.Serial;

/**
 * The top-level {@link FlowRuntimeException} for non-recoverable errors.
 */
public class FlowRuntimeException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public FlowRuntimeException() {
    }

    public FlowRuntimeException(String message) {
        super(message);
    }

    public FlowRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }

    public FlowRuntimeException(Throwable cause) {
        super(cause);
    }

}
