package org.flow.core.exceptions;

import java.io.Serial;

public class ResourceAccessDeniedException extends FlowRuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public ResourceAccessDeniedException() {
    }

    public ResourceAccessDeniedException(String message) {
        super(message);
    }
}
