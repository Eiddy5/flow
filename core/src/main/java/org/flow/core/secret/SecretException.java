package org.flow.core.secret;

import java.io.Serial;

import org.flow.core.exceptions.FlowRuntimeException;

/**
 * Top-level exception for Secrets.
 */
public class SecretException extends FlowRuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public SecretException(String message) {
        super(message);
    }
}
