package org.cses.flow.core.domains;

import org.cses.flow.core.exceptions.WorkflowException;

/**
 * Optimistic-lock capability for an identified aggregate or entity.
 *
 * <p>This capability advances and verifies a technical lock version. It does
 * not represent a database row lock or a business lifecycle state.</p>
 */
public interface Lockable<T extends Lockable<T>> extends Identified {

    long lockVersion();

    T lock();

    default boolean hasLockVersion(long expectedLockVersion) {
        return lockVersion() == expectedLockVersion;
    }

    default void requireLockVersion(long expectedLockVersion) {
        if (!hasLockVersion(expectedLockVersion)) {
            throw new WorkflowException(
                "Lock conflict for " + identifier()
                    + ": expected " + expectedLockVersion
                    + " but was " + lockVersion()
            );
        }
    }
}
