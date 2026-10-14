package org.flow.core.repositories;

import java.util.List;
import java.util.Optional;

import org.flow.core.lock.Lock;

/**
 * Low lever repository for locks.
 * It should never be used directly but only via the {@link org.flow.core.lock.LockService}.
 */
public interface LockRepositoryInterface {
    Optional<Lock> findById(String category, String id);

    boolean create(Lock newLock);

    default void delete(Lock existing) {
        deleteById(existing.getCategory(), existing.getId());
    }

    void deleteById(String category, String id);

    List<Lock> deleteByOwner(String owner);
}
