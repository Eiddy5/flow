package org.flow.core.exceptions;

import java.io.Serial;

public class InvalidSourceSearchQueryException extends FlowRuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidSourceSearchQueryException(final String message) {
        super(message);
    }
}
