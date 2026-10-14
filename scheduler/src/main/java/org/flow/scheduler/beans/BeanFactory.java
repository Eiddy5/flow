package org.flow.scheduler.beans;

import org.flow.core.repositories.FlowRepositoryInterface;
import org.flow.core.scheduler.SchedulerConfiguration;
import org.flow.core.scheduler.store.TriggerStateStore;
import org.flow.scheduler.stores.CachedFlowMetaStore;
import org.flow.scheduler.stores.CachedTriggerStateStore;
import org.flow.scheduler.stores.DefaultFlowMetaStore;
import org.flow.scheduler.stores.FlowMetaStore;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Secondary;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

@Factory
public class BeanFactory {

    @Inject
    SchedulerConfiguration schedulerConfiguration;

    @Inject
    FlowRepositoryInterface flowRepositoryInterface;

    @Singleton
    public FlowMetaStore flowMetaStore() {
        DefaultFlowMetaStore store = new DefaultFlowMetaStore(schedulerConfiguration, flowRepositoryInterface);
        return schedulerConfiguration.isCacheDisable() ? store : new CachedFlowMetaStore(store, schedulerConfiguration);
    }

    @Named("cached")
    @Singleton
    @Secondary
    public TriggerStateStore triggerStateStore(@Primary TriggerStateStore triggerStateStore, SchedulerConfiguration configuration) {
        return configuration.isCacheDisable() ? triggerStateStore : new CachedTriggerStateStore(triggerStateStore, configuration);
    }
}
