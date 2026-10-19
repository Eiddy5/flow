package org.flow.core.services;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.flow.core.models.Setting;
import org.flow.core.repositories.SettingRepositoryInterface;
import org.flow.core.utils.VersionProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VersionServiceTest {

    @Mock
    private SettingRepositoryInterface settingRepository;

    @Mock
    private VersionProvider versionProvider;

    @Captor
    private ArgumentCaptor<Setting> settingCaptor;

    private VersionService versionService;

    @BeforeEach
    void setUp() {
        versionService = new VersionService(() -> settingRepository, () -> versionProvider);
    }

    @Test
    void shouldReturnInstanceVersionWhenPresent() {
        // Given
        Setting setting = Setting.builder()
            .key(Setting.INSTANCE_VERSION)
            .value("1.2.3")
            .build();
        when(settingRepository.findByKey(Setting.INSTANCE_VERSION)).thenReturn(Optional.of(setting));

        // When
        Optional<String> version = versionService.getInstanceVersion();

        // Then
        assertThat(version).isPresent();
        assertThat(version.get()).isEqualTo("1.2.3");
        verify(settingRepository).findByKey(Setting.INSTANCE_VERSION);
    }

    @Test
    void shouldReturnEmptyOptionalWhenVersionNotPresent() {
        // Given
        when(settingRepository.findByKey(Setting.INSTANCE_VERSION)).thenReturn(Optional.empty());

        // When
        Optional<String> version = versionService.getInstanceVersion();

        // Then
        assertThat(version).isEmpty();
        verify(settingRepository).findByKey(Setting.INSTANCE_VERSION);
    }

    @Test
    void shouldSaveVersionWhenNotPresent() {
        // Given
        String softwareVersion = "1.2.3";
        when(settingRepository.findByKey(Setting.INSTANCE_VERSION)).thenReturn(Optional.empty());
        when(versionProvider.getVersion()).thenReturn(softwareVersion);

        // When
        versionService.maybeSaveOrUpdateInstanceVersion();

        // Then
        verify(settingRepository).save(settingCaptor.capture());
        Setting savedSetting = settingCaptor.getValue();
        assertThat(savedSetting.getKey()).isEqualTo(Setting.INSTANCE_VERSION);
        assertThat(savedSetting.getValue()).isEqualTo(softwareVersion);
    }

    @Test
    void shouldUpdateVersionWhenDifferent() {
        // Given
        String oldVersion = "1.3.3";
        String newVersion = "1.3.4";
        Setting existingSetting = Setting.builder()
            .key(Setting.INSTANCE_VERSION)
            .value(oldVersion)
            .build();
        when(settingRepository.findByKey(Setting.INSTANCE_VERSION)).thenReturn(Optional.of(existingSetting));
        when(versionProvider.getVersion()).thenReturn(newVersion);

        // When
        versionService.maybeSaveOrUpdateInstanceVersion();

        // Then
        assertThat(savedValue(Setting.INSTANCE_VERSION)).isEqualTo(newVersion);
    }

    @Test
    void shouldNotSaveVersionWhenAlreadyUpToDate() {
        // Given
        String currentVersion = "1.2.3";
        Setting existingSetting = Setting.builder()
            .key(Setting.INSTANCE_VERSION)
            .value(currentVersion)
            .build();
        when(settingRepository.findByKey(Setting.INSTANCE_VERSION)).thenReturn(Optional.of(existingSetting));
        when(versionProvider.getVersion()).thenReturn(currentVersion);

        // When
        versionService.maybeSaveOrUpdateInstanceVersion();

        // Then
        verify(settingRepository, never()).save(any(Setting.class));
    }

    @Test
    void shouldHandleNumericVersionValue() {
        // Given - version stored as Integer instead of String
        Setting setting = Setting.builder()
            .key(Setting.INSTANCE_VERSION)
            .value(123)
            .build();
        when(settingRepository.findByKey(Setting.INSTANCE_VERSION)).thenReturn(Optional.of(setting));

        // When
        Optional<String> version = versionService.getInstanceVersion();

        // Then
        assertThat(version).isPresent();
        assertThat(version.get()).isEqualTo("123");
        verify(settingRepository).findByKey(Setting.INSTANCE_VERSION);
    }

    @Test
    void shouldSaveSnapshotVersion() {
        // Given
        String snapshotVersion = "1.3.0-SNAPSHOT";
        when(settingRepository.findByKey(Setting.INSTANCE_VERSION)).thenReturn(Optional.empty());
        when(versionProvider.getVersion()).thenReturn(snapshotVersion);

        // When
        versionService.maybeSaveOrUpdateInstanceVersion();

        // Then
        verify(settingRepository).save(settingCaptor.capture());
        Setting savedSetting = settingCaptor.getValue();
        assertThat(savedSetting.getKey()).isEqualTo(Setting.INSTANCE_VERSION);
        assertThat(savedSetting.getValue()).isEqualTo(snapshotVersion);
    }

    @Test
    void shouldUpdateFromSnapshotToReleaseVersion() {
        // Given
        String snapshotVersion = "1.3.0-SNAPSHOT";
        String releaseVersion = "1.3.0";
        Setting existingSetting = Setting.builder()
            .key(Setting.INSTANCE_VERSION)
            .value(snapshotVersion)
            .build();
        when(settingRepository.findByKey(Setting.INSTANCE_VERSION)).thenReturn(Optional.of(existingSetting));
        when(versionProvider.getVersion()).thenReturn(releaseVersion);

        // When
        versionService.maybeSaveOrUpdateInstanceVersion();

        // Then
        assertThat(savedValue(Setting.INSTANCE_VERSION)).isEqualTo(releaseVersion);
    }

    @Test
    void shouldThrowForTooOldVersion() {
        // Given
        String oldVersion = "0.22.3";
        String newVersion = "2.0.0";
        Setting existingSetting = Setting.builder()
            .key(Setting.INSTANCE_VERSION)
            .value(oldVersion)
            .build();
        when(settingRepository.findByKey(Setting.INSTANCE_VERSION)).thenReturn(Optional.of(existingSetting));
        when(versionProvider.getVersion()).thenReturn(newVersion);

        // When
        var exception = assertThrows(IllegalStateException.class, () -> versionService.maybeSaveOrUpdateInstanceVersion());

        // Then
        assertThat(exception.getMessage()).isEqualTo("Instance version 0.22.3 is too old and cannot be migrated to 2.0.0, please upgrade to at least 1.3.0 first");
    }

    @Test
    void shouldNotThrowForADoubleDigitMinorAboveTheMinVersion() {
        // "1.10.0" sorts before "1.3.0" as a string, but it is newer and must still be migrated.
        givenInstanceVersion("1.10.0");
        when(versionProvider.getVersion()).thenReturn("2.0.0");

        versionService.maybeSaveOrUpdateInstanceVersion();

        assertThat(savedValue(Setting.INSTANCE_VERSION)).isEqualTo("2.0.0");
    }

    @Test
    void shouldNotThrowForAnUnparsableStoredVersion() {
        // A development build reports "Snapshot": it cannot be placed against the floor, and refusing
        // to start on it would be worse than letting it through.
        givenInstanceVersion("Snapshot");
        when(versionProvider.getVersion()).thenReturn("2.0.0");

        versionService.maybeSaveOrUpdateInstanceVersion();

        assertThat(savedValue(Setting.INSTANCE_VERSION)).isEqualTo("2.0.0");
    }

    @Test
    void shouldNotThrowForAPreReleaseOfTheMinVersion() {
        // 1.2.0-rc1 is older than the floor, but 1.3.0-SNAPSHOT carries the schema of 1.3.0.
        givenInstanceVersion("1.3.0-SNAPSHOT");
        when(versionProvider.getVersion()).thenReturn("2.0.0");

        versionService.maybeSaveOrUpdateInstanceVersion();

        assertThat(savedValue(Setting.INSTANCE_VERSION)).isEqualTo("2.0.0");
    }

    private void givenInstanceVersion(String version) {
        when(settingRepository.findByKey(Setting.INSTANCE_VERSION)).thenReturn(Optional.of(
            Setting.builder().key(Setting.INSTANCE_VERSION).value(version).build()
        ));
    }

    private Object savedValue(String key) {
        verify(settingRepository, atLeastOnce()).save(settingCaptor.capture());
        List<Setting> saved = settingCaptor.getAllValues().stream().filter(s -> s.getKey().equals(key)).toList();
        assertThat(saved).as("a setting saved under %s", key).hasSize(1);
        return saved.getFirst().getValue();
    }
}
