package org.flow.core.lock;

import org.flow.core.exceptions.FlowRuntimeException;

public class LockException extends FlowRuntimeException {
    private static final long serialVersionUID = 1L;

    public LockException(String message) {
        super(message);
    }

    public LockException(Throwable cause) {
        super(cause);
    }
}
