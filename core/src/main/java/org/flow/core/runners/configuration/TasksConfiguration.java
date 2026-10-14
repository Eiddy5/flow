package org.flow.core.runners.configuration;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Nullable;

@ConfigurationProperties("flow.tasks")
public record TasksConfiguration(
    @Nullable TmpDir tmpDir) {

    @ConfigurationProperties("tmp-dir")
    public record TmpDir(@Nullable String path) {
    }
}
