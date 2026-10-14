package org.flow.executor.configuration;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;

@ConfigurationProperties("flow.executor")
public record ExecutorConfiguration(
    @Bindable(defaultValue = "0") Integer threadCount) {
}
