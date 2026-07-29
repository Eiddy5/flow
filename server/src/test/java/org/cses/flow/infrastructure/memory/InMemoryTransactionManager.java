package org.cses.flow.infrastructure.memory;

import jakarta.inject.Singleton;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Gives test memory repositories the atomic visibility promised by a command
 * transaction without adding memory infrastructure to production.
 */
@Singleton
public final class InMemoryTransactionManager {

    private final ReentrantReadWriteLock lock =
        new ReentrantReadWriteLock(true);
    private final List<InMemoryTransactionalResource> resources =
        new ArrayList<>();

    public void register(InMemoryTransactionalResource resource) {
        lock.writeLock().lock();
        try {
            if (resources.stream().noneMatch(existing -> existing == resource)) {
                resources.add(resource);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public <T> T read(SqlSupplier<T> operation) throws SQLException {
        lock.readLock().lock();
        try {
            return operation.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    public <T> T write(SqlSupplier<T> operation) throws SQLException {
        lock.writeLock().lock();
        Map<InMemoryTransactionalResource, Object> snapshots =
            new IdentityHashMap<>();
        try {
            resources.forEach(resource ->
                snapshots.put(resource, resource.snapshot())
            );
            return operation.get();
        } catch (RuntimeException | Error | SQLException failure) {
            restore(snapshots);
            throw failure;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void write(SqlRunnable operation) throws SQLException {
        write(() -> {
            operation.run();
            return null;
        });
    }

    private void restore(
        Map<InMemoryTransactionalResource, Object> snapshots
    ) {
        for (int index = resources.size() - 1; index >= 0; index--) {
            InMemoryTransactionalResource resource = resources.get(index);
            if (snapshots.containsKey(resource)) {
                resource.restore(snapshots.get(resource));
            }
        }
    }

    @FunctionalInterface
    public interface SqlSupplier<T> {

        T get() throws SQLException;
    }

    @FunctionalInterface
    public interface SqlRunnable {

        void run() throws SQLException;
    }
}
