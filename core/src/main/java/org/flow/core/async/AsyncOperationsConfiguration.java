package org.flow.core.async;

import java.time.Duration;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;

@ConfigurationProperties("flow.async-operations")
public record AsyncOperationsConfiguration(
    @Bindable(defaultValue = "PT30S") Duration waitTimeout) {
}
