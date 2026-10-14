package org.flow.core.utils;

import java.util.Optional;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.flow.core.models.Setting;
import org.flow.core.repositories.SettingRepositoryInterface;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@MicronautTest
public class EditionProviderTest {
    @Inject
    private EditionProvider editionProvider;

    @Inject
    private SettingRepositoryInterface settingRepository;

    protected EditionProvider.Edition expectedEdition() {
        return EditionProvider.Edition.OSS;
    }

    @Test
    void shouldReturnCurrentEdition() {
        Assertions.assertEquals(expectedEdition(), editionProvider.get());
    }

    @Test
    void shouldPersistEditionInSettings() {
        editionProvider.persistEdition(settingRepository);

        Optional<Setting> editionSettings = settingRepository.findByKey(Setting.INSTANCE_EDITION);
        assertThat(editionSettings).isPresent();
        assertThat(editionSettings.get().getValue()).isEqualTo(expectedEdition().name());
    }
}