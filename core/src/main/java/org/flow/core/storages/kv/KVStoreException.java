package org.flow.core.storages.kv;

import java.io.Serial;

import org.flow.core.exceptions.FlowRuntimeException;

/**
 * The base class for all other KVStore exceptions.
 */
public class KVStoreException extends FlowRuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public KVStoreException() {
    }

    public KVStoreException(String message) {
        super(message);
    }

    public KVStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public KVStoreException(Throwable cause) {
        super(cause);
    }
}
