package org.flow.core.services;

import java.util.Optional;

import org.flow.core.models.Setting;
import org.flow.core.repositories.SettingRepositoryInterface;
import org.flow.core.utils.Version;
import org.flow.core.utils.VersionProvider;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Service responsible for managing the version information of the Flow instance.
 * <p>
 * It interacts with the settings repository to store and retrieve the instance version.
 */
@Singleton
@Slf4j
public class VersionService {
    private static final String MIN_VERSION = "1.3.0";

    private final Provider<SettingRepositoryInterface> settingRepository;
    private final Provider<VersionProvider> versionProvider;

    /**
     * Creates a new {@link VersionService} instance.
     *
     * @param settingRepository the repository to manage settings.
     */
    @Inject
    public VersionService(final Provider<SettingRepositoryInterface> settingRepository,
        final Provider<VersionProvider> versionProvider) {
        this.settingRepository = settingRepository;
        this.versionProvider = versionProvider;
    }

    /**
     * Retrieves the current instance version from the settings repository.
     *
     * @return an {@link Optional} containing the instance version if it exists, or an empty {@link Optional} if it does not.
     */
    public Optional<String> getInstanceVersion() {
        return settingRepository.get().findByKey(Setting.INSTANCE_VERSION).map(Setting::getValue).map(Object::toString);
    }

    /**
     * Checks if the current instance version is stored in the settings repository and saves
     * it if it's not present or if it differs from the software version.
     */
    public void maybeSaveOrUpdateInstanceVersion() {
        Optional<String> settingVersion = getInstanceVersion();
        final String softwareVersion = versionProvider.get().getVersion();
        if (settingVersion.isEmpty() || !settingVersion.get().equals(softwareVersion)) {
            // A version this old cannot be migrated. Compared field by field rather than as a string:
            // "1.10.0" sorts before "1.3.0" lexicographically, which would reject an instance that is
            // in fact newer than the floor.
            if (settingVersion.filter(VersionService::isOlderThanMinVersion).isPresent()) {
                throw new IllegalStateException(
                    String.format(
                        "Instance version %s is too old and cannot be migrated to %s, please upgrade to at least %s first",
                        settingVersion.get(),
                        softwareVersion,
                        MIN_VERSION
                    )
                );
            }

            log.info("Updating instance version from {} to {}", settingVersion.orElse("none"), softwareVersion);

            settingRepository.get().save(
                Setting.builder()
                    .key(Setting.INSTANCE_VERSION)
                    .value(softwareVersion)
                    .build()
            );
        }
    }

    /**
     * Whether the given version predates {@link #MIN_VERSION}.
     * <p>
     * Only the release part is compared: {@code 1.3.0-SNAPSHOT} carries the schema of {@code 1.3.0} and
     * must not be turned away as older than it. An unparsable version cannot be placed against the floor
     * at all, and is let through rather than blocking the startup of a build that reports {@code Snapshot}.
     */
    private static boolean isOlderThanMinVersion(final String version) {
        try {
            return Version.of(releaseOf(version)).isBefore(Version.of(MIN_VERSION));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String releaseOf(final String version) {
        int qualifier = version.indexOf('-');
        return qualifier > 0 ? version.substring(0, qualifier) : version;
    }

}
