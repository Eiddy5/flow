package org.flow.core.utils;

import org.junit.jupiter.api.Test;

import org.flow.core.repositories.SettingRepositoryInterface;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@MicronautTest
class VersionProviderTest {
    @Inject
    private VersionProvider versionProvider;

    @Inject
    private SettingRepositoryInterface settingRepository;

    @Test
    void shouldResolveVersion() {
        assertThat(versionProvider.getVersion()).isNotNull();
    }
}