package org.flow.core.contexts.configuration;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.bind.annotation.Bindable;

@ConfigurationProperties("flow")
public record FlowConfiguration(
    @Nullable String url,
    @Nullable FlowEnvironment environment,
    @Bindable(defaultValue = "0") Integer allocatedCpuCores) {

    @ConfigurationProperties("environment")
    public record FlowEnvironment(
        @Nullable String name,
        @Nullable String color) {
    }
}
