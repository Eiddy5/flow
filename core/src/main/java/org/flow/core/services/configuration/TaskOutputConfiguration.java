package org.flow.core.services.configuration;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;

@ConfigurationProperties("flow.task.outputs")
public record TaskOutputConfiguration(
    @Bindable(defaultValue = "-1") Integer limit) {
}
