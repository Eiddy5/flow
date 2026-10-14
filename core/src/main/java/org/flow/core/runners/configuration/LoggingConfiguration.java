package org.flow.core.runners.configuration;

import java.util.List;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Nullable;

/**
 * Configures which execution {@link org.flow.core.models.Label} keys are propagated to the logging MDC context.
 */
@ConfigurationProperties("flow.logging")
public record LoggingConfiguration(
    @Nullable List<String> labels) {
}
