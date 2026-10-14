package org.flow.core.migration;

import org.flow.core.exceptions.FlowRuntimeException;

/**
 * Thrown by {@link MigrationRunnerInterface#runOrFailIfLocked()} when the migration lock is
 * already held by another process.
 *
 * <p>
 * This is used by the {@code flow migrate run} CLI command, which is always single-node
 * and should fail immediately instead of waiting for the lock.
 */
public class MigrationLockedException extends FlowRuntimeException {

    private static final long serialVersionUID = 1L;

    public MigrationLockedException() {
        super(
            "Migration lock is held by another process. " +
                "Another instance may be running migrations. " +
                "If this is unexpected, use 'flow migrate unlock' to force-release the lock."
        );
    }
}
