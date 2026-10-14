package org.flow.core.contexts.configuration;

import java.util.Optional;

import io.micronaut.context.annotation.ConfigurationProperties;

@ConfigurationProperties("flow.storage")
public record StorageConfiguration(
    Optional<String> type) {
}
