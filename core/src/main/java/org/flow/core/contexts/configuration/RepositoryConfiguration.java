package org.flow.core.contexts.configuration;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Nullable;

@ConfigurationProperties("flow.repository")
public record RepositoryConfiguration(
    @Nullable String type) {
}
