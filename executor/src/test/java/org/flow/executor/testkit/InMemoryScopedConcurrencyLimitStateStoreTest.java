package org.flow.executor.testkit;

import java.util.Map;

import org.flow.core.metrics.MetricConfig;
import org.flow.core.metrics.MetricRegistry;
import org.flow.core.runners.ExecutionQueuedStateStore;
import org.flow.executor.ConcurrencyLimitStateStore;
import org.flow.executor.ScopedConcurrencyLimitStateStoreContract;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Proves the testkit's {@link InMemoryConcurrencyLimitStateStore} honors the same multi-scope
 * semantics as the production EE stores — widest-scope pop, blocked-candidate skip, scope isolation.
 */
class InMemoryScopedConcurrencyLimitStateStoreTest extends ScopedConcurrencyLimitStateStoreContract {
    private final InMemoryConcurrencyLimitStateStore store = new InMemoryConcurrencyLimitStateStore();
    private final InMemoryExecutionQueuedStateStore queuedStore = new InMemoryExecutionQueuedStateStore();
    private final MetricRegistry metricRegistry = new MetricRegistry(new SimpleMeterRegistry(), new MetricConfig(null, null, null, Map.of()));

    @Override
    protected ConcurrencyLimitStateStore store() {
        return store;
    }

    @Override
    protected ExecutionQueuedStateStore queuedStore() {
        return queuedStore;
    }

    @Override
    protected MetricRegistry metricRegistry() {
        return metricRegistry;
    }
}
