package org.flow.core.storages.kv.configuration;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;

@ConfigurationProperties("flow.kv.purge-expired")
public record KVPurgeConfiguration(
    @Bindable(defaultValue = "1000") Integer batchSize) {
}
