package org.flow.executor.testkit;

import org.flow.core.runners.ExecutionQueuedStateStore;
import org.flow.executor.ConcurrencyLimitStateStore;
import org.flow.executor.ConcurrencyLimitStateStoreContract;

/**
 * Proves the testkit's {@link InMemoryConcurrencyLimitStateStore} honors the same behavioral
 * contract as the production JDBC implementations — plain JUnit, no Micronaut, no database.
 */
class InMemoryConcurrencyLimitStateStoreTest extends ConcurrencyLimitStateStoreContract {

    private final InMemoryConcurrencyLimitStateStore store = new InMemoryConcurrencyLimitStateStore();
    private final InMemoryExecutionQueuedStateStore queuedStore = new InMemoryExecutionQueuedStateStore();

    @Override
    protected ConcurrencyLimitStateStore store() {
        return store;
    }

    @Override
    protected ExecutionQueuedStateStore queuedStore() {
        return queuedStore;
    }
}
