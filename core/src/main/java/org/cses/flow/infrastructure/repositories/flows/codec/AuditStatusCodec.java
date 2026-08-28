package org.cses.flow.infrastructure.repositories.flows.codec;

import org.paas.session.RecordState;

import java.util.Objects;

/**
 * Converts the extensible persisted Audit status name to its domain value.
 */
public class AuditStatusCodec {

    private AuditStatusCodec() {
    }

    public static String encode(RecordState status) {
        return Objects.requireNonNull(status, "Audit status").getName();
    }

    public static RecordState decode(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "Persisted " + field + " must not be blank"
            );
        }
        RecordState status = RecordState.fromString(value.trim());
        if (status == null) {
            throw new IllegalStateException(
                "Persisted " + field + " is unsupported: " + value
            );
        }
        return status;
    }
}
