package org.flow.core.plugins;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.eclipse.aether.transfer.TransferListener;

import org.flow.core.contexts.MavenPluginRepositoryConfig;
import org.flow.core.exceptions.FlowRuntimeException;

import io.micronaut.context.annotation.Value;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import static org.flow.core.plugins.PluginManager.createLocalRepositoryIfNotExist;

/**
 * A {@link PluginManager} implementation managing plugin artifacts on local storage.
 */
@Singleton
@Slf4j
public class LocalPluginManager implements PluginManager {

    private final Provider<PluginRegistry> pluginRegistryProvider;

    private final MavenPluginDownloader mavenPluginDownloader;

    private final Path localRepositoryPath;

    /**
     * 使用默认插件仓库创建管理器，不自动向插件注册表注册已安装制品。
     *
     * @param mavenPluginDownloader 下载和解析插件的组件，不能为 {@code null}
     * @throws RuntimeException 默认插件仓库目录无法创建时抛出
     */
    public LocalPluginManager(final MavenPluginDownloader mavenPluginDownloader) {
        this(null, mavenPluginDownloader, null);
    }

    /**
     * 使用显式插件仓库创建管理器；路径为空时使用临时目录下的默认插件仓库。
     * 构造时创建缺失的目录，插件仓库不再从文件存储配置推导。
     *
     * @param pluginRegistryProvider 插件注册表提供者；为 {@code null} 时不自动注册制品
     * @param mavenPluginDownloader 下载和解析插件的组件，不能为 {@code null}
     * @param localRepositoryPath 插件仓库路径；为 {@code null} 时使用默认临时目录
     * @throws RuntimeException 插件仓库目录无法创建时抛出
     */
    @Inject
    public LocalPluginManager(final Provider<PluginRegistry> pluginRegistryProvider,
        final MavenPluginDownloader mavenPluginDownloader,
        @Nullable @Value("${flow.plugins.management.localRepositoryPath}") final String localRepositoryPath) {
        this.pluginRegistryProvider = pluginRegistryProvider;
        this.mavenPluginDownloader = mavenPluginDownloader;
        this.localRepositoryPath = PluginManager.getLocalManagedRepositoryPathOrDefault(localRepositoryPath);
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public void start() {
        // Re-register plugins installed by previous runs: nothing else scans the managed repository
        // at boot, so without this an installed plugin would vanish after a restart.
        if (pluginRegistryProvider != null) {
            pluginRegistryProvider.get().registerIfAbsent(localRepositoryPath);
        }
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public boolean isReady() {
        return true;
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public List<PluginArtifactMetadata> list() {
        try (Stream<Path> files = Files.list(localRepositoryPath)) {
            return files
                .filter(file -> Files.isRegularFile(file) && Files.isReadable(file) && file.toString().endsWith(".jar"))
                .map(file ->
                {
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                        return new PluginArtifactMetadata(
                            file.toUri(),
                            file.getFileName().toString(),
                            attrs.size(),
                            attrs.creationTime().toMillis(),
                            attrs.lastModifiedTime().toMillis()
                        );
                    } catch (IOException e) {
                        log.warn("Failed to get file attribute from file {}", file.getFileName());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
        } catch (IOException e) {
            throw new FlowRuntimeException(e);
        }
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public PluginArtifact install(PluginArtifact artifact,
        List<MavenPluginRepositoryConfig> repositoryConfigs,
        boolean installForRegistration,
        @Nullable Path localRepositoryPath) {
        return install(artifact, repositoryConfigs, installForRegistration, localRepositoryPath, null);
    }

    /**
     * Installs the given artifact, reporting byte-level transfer progress to the provided listener.
     */
    public PluginArtifact install(PluginArtifact artifact,
        List<MavenPluginRepositoryConfig> repositoryConfigs,
        boolean installForRegistration,
        @Nullable Path localRepositoryPath,
        @Nullable TransferListener transferListener) {
        Objects.requireNonNull(artifact, "cannot install null artifact");

        log.info("Installing managed plugin artifact '{}'", artifact);
        final PluginArtifact resolvedPluginArtifact = mavenPluginDownloader.resolve(artifact.toString(), repositoryConfigs, transferListener);

        return install(resolvedPluginArtifact, installForRegistration, localRepositoryPath);
    }

    private PluginArtifact install(final PluginArtifact artifact,
        final boolean installForRegistration,
        Path localRepositoryPath) {

        localRepositoryPath = createLocalRepositoryIfNotExist(Optional.ofNullable(localRepositoryPath).orElse(this.localRepositoryPath));
        Path localPluginPath = getLocalPluginPath(localRepositoryPath, artifact);

        try {
            Files.createDirectories(localPluginPath.getParent());
            Files.copy(Path.of(artifact.uri()), localPluginPath, StandardCopyOption.REPLACE_EXISTING);

            if (installForRegistration && pluginRegistryProvider != null) {
                pluginRegistryProvider.get().register(localRepositoryPath);
            }
            log.info("Plugin '{}' installed successfully in local repository: {}", artifact, localRepositoryPath);
            return artifact.relocateTo(localPluginPath.toUri());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public PluginArtifact install(File file, boolean installForRegistration, @Nullable Path localRepositoryPath, boolean forceInstallOnExistingVersions) {
        try {
            PluginArtifact artifact = PluginArtifact.fromFile(file);
            log.info("Installing managed plugin artifact '{}'", artifact);
            return install(artifact, installForRegistration, localRepositoryPath);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public List<PluginArtifact> install(List<PluginArtifact> artifacts,
        List<MavenPluginRepositoryConfig> repositoryConfigs,
        boolean refreshPluginRegistry,
        @Nullable Path localRepositoryPath) {
        return install(artifacts, repositoryConfigs, refreshPluginRegistry, localRepositoryPath, null);
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public List<PluginArtifact> install(List<PluginArtifact> artifacts,
        List<MavenPluginRepositoryConfig> repositoryConfigs,
        boolean refreshPluginRegistry,
        @Nullable Path localRepositoryPath,
        @Nullable TransferListener transferListener) {
        return artifacts.stream()
            .map(artifact -> install(artifact, repositoryConfigs, refreshPluginRegistry, localRepositoryPath, transferListener))
            .toList();
    }

    private Path getLocalPluginPath(final Path localRepositoryPath, final PluginArtifact artifact) {
        return localRepositoryPath.resolve(artifact.toFileName());
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public List<PluginArtifact> uninstall(List<PluginArtifact> artifacts, boolean refreshPluginRegistry, @Nullable Path localRepositoryPath) {

        final Path repositoryPath = Optional.ofNullable(localRepositoryPath).orElse(this.localRepositoryPath);

        final List<PluginArtifact> uninstalled = artifacts.stream()
            .map(artifact -> doUninstall(artifact, repositoryPath) ? artifact : null)
            .filter(Objects::nonNull)
            .toList();

        if (refreshPluginRegistry && pluginRegistryProvider != null) {
            pluginRegistryProvider.get().register(localRepositoryPath);
        }
        return uninstalled;
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public List<PluginResolutionResult> resolveVersions(List<PluginArtifact> artifacts) {
        return mavenPluginDownloader.resolveVersions(artifacts);
    }

    private boolean doUninstall(final PluginArtifact artifact, final Path localRepositoryPath) {

        final Path localPluginPath = getLocalPluginPath(localRepositoryPath, artifact);

        if (Files.exists(localPluginPath)) {
            log.info("Removing plugin artifact from local repository: {}", localPluginPath);
            if (pluginRegistryProvider != null) {
                final PluginRegistry registry = pluginRegistryProvider.get();
                // Unregister all plugins from registry
                registry.unregister(new ArrayList<>(registry.plugins((plugin) ->
                {
                    if (plugin.getClassLoader() instanceof PluginClassLoader pluginClassLoader) {
                        URI location = URI.create(pluginClassLoader.location());
                        return localPluginPath.equals(Path.of(location));
                    }
                    return false;
                })));
            }

            try {
                if (Files.deleteIfExists(localPluginPath)) {
                    log.info("Removed plugin artifact from local repository: {}", localPluginPath);
                }
                return true;
            } catch (IOException e) {
                log.error(
                    "Unexpected error while removing plugin artifact from plugin repository: {}",
                    localPluginPath,
                    e
                );
                return false;
            }
        }
        return false;
    }
}
