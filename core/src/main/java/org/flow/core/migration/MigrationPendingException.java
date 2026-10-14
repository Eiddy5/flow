package org.flow.core.migration;

import java.util.List;

import org.flow.core.exceptions.FlowRuntimeException;

/**
 * Thrown at startup when pending migrations exist and automatic migration is disabled.
 *
 * <p>
 * Used by the EE migration runner override when {@code flow.migration.auto=false}
 * (the EE default) and there are unapplied migration scripts. The application refuses to start
 * until the operator either runs {@code flow migrate run} or sets
 * {@code flow.migration.auto=true}.
 */
public class MigrationPendingException extends FlowRuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<String> pendingScriptIds;

    /**
     * Creates a new {@link MigrationPendingException}.
     *
     * @param pendingScriptIds the IDs of the migration scripts that have not yet been applied
     */
    public MigrationPendingException(final List<String> pendingScriptIds) {
        super(buildMessage(pendingScriptIds));
        this.pendingScriptIds = List.copyOf(pendingScriptIds);
    }

    /**
     * Returns the IDs of the pending migration scripts.
     *
     * @return an unmodifiable list of pending script IDs
     */
    public List<String> pendingScriptIds() {
        return pendingScriptIds;
    }

    private static String buildMessage(final List<String> pendingScriptIds) {
        return """
            Flow cannot start: one or more database schema migrations are needed first.
            Migration scripts to apply: %s
            To run the migrations, either:
              - Run: flow migrate run
              - Or enable automatic migration by setting: flow.migration.auto=true
            """.formatted(pendingScriptIds);
    }
}
