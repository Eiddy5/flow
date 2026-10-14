package org.flow.core.plugins.configuration;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Nullable;

@ConfigurationProperties("flow.plugins")
public record PluginsConfiguration(
    @Nullable String localRepositoryPath) {
}
