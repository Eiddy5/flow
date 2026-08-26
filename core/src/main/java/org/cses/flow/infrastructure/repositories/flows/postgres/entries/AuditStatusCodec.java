package org.cses.flow.infrastructure.repositories.flows.postgres.entries;

import org.paas.session.RecordState;

import java.util.Objects;

/**
 * Converts the extensible persisted Audit status name to its domain value.
 */
final class AuditStatusCodec {

    private AuditStatusCodec() {
    }

    static String encode(RecordState status) {
        return Objects.requireNonNull(status, "Audit status").getName();
    }

    static RecordState decode(String value, String field) {
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
