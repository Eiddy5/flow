package org.flow.executor.testkit;

import org.flow.core.models.triggers.multipleflows.MultipleConditionStateStore;
import org.flow.core.models.triggers.multipleflows.MultipleConditionStateStoreContract;

/**
 * Holds {@link InMemoryMultipleConditionStateStore} to the same
 * {@link MultipleConditionStateStoreContract} as the JDBC and Elasticsearch backends — the fake
 * stays provably faithful to production. Plain JUnit, no Micronaut.
 */
class InMemoryMultipleConditionStateStoreTest extends MultipleConditionStateStoreContract {

    private final InMemoryMultipleConditionStateStore store = new InMemoryMultipleConditionStateStore();

    @Override
    protected MultipleConditionStateStore store() {
        return store;
    }
}
