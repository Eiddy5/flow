package org.flow.core.contexts.configuration;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;

@ConfigurationProperties("flow.system-flows")
public record SystemFlowsConfiguration(
    @Bindable(defaultValue = DEFAULT_NAMESPACE) String namespace) {

    public static final String DEFAULT_NAMESPACE = "system";
}
