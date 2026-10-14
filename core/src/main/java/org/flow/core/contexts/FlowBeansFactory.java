package org.flow.core.contexts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.flow.core.contexts.configuration.RepositoryConfiguration;
import org.flow.core.contexts.configuration.StorageConfiguration;
import org.flow.core.exceptions.FlowRuntimeException;
import org.flow.core.plugins.DefaultPluginRegistry;
import org.flow.core.plugins.PluginRegistry;
import org.flow.core.repositories.LogDataStoreInterface;
import org.flow.core.repositories.log.LogDataStoreInterfaceFactory;
import org.flow.core.repositories.log.LogsConfig;
import org.flow.core.storages.StorageInterface;
import org.flow.core.storages.StorageInterfaceFactory;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.convert.format.MapFormat;
import io.micronaut.core.naming.conventions.StringConvention;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.Validator;

import static org.flow.core.repositories.log.LogDataStoreInterfaceFactory.FLOW_LOGS_TYPE_CONFIG;
import static org.flow.core.storages.StorageInterfaceFactory.FLOW_STORAGE_TYPE_CONFIG;

@Factory
public class FlowBeansFactory {

    @Inject
    Validator validator;

    @Inject
    StorageConfig storageConfig;

    @Inject
    protected StorageConfiguration storageConfiguration;

    @Inject
    LogsConfig logsConfig;

    @Inject
    RepositoryConfiguration repositoryConfiguration;

    /**
     * 创建原生插件注册表并加载配置的外部目录，返回前完成插件发现。
     * @param pluginsPath 插件目录；空字符串只加载 classpath 插件，非空目录必须存在
     * @return 已加载配置目录的原生插件注册表
     * @throws IllegalArgumentException 插件目录不存在或不是目录时抛出
     */
    @Requires(missingBeans = PluginRegistry.class)
    @Singleton
    public PluginRegistry pluginRegistry(@Value("${flow.plugins.path:}") String pluginsPath) {
        PluginRegistry registry = DefaultPluginRegistry.getOrCreate();
        if (!pluginsPath.isBlank()) {
            Path path = Path.of(pluginsPath).toAbsolutePath().normalize();
            if (!Files.isDirectory(path)) {
                throw new IllegalArgumentException("插件目录不存在：" + path);
            }
            registry.registerIfAbsent(path);
        }
        return registry;
    }

    @Singleton
    public StorageInterfaceFactory storageInterfaceFactory(final PluginRegistry pluginRegistry) {
        return new StorageInterfaceFactory(pluginRegistry, validator);
    }

    @Requires(missingBeans = StorageInterface.class)
    @Singleton
    @Bean(preDestroy = "close")
    public StorageInterface storageInterface(final StorageInterfaceFactory storageInterfaceFactory) throws IOException {
        String pluginId = getStoragePluginId(storageInterfaceFactory);
        return storageInterfaceFactory.make(null, pluginId, storageConfig.getStorageConfig(pluginId));
    }

    public String getStoragePluginId(StorageInterfaceFactory storageInterfaceFactory) {
        return storageConfiguration.type().orElseThrow(
            () -> new FlowRuntimeException(
                String.format(
                    "No storage configured through the application property '%s'. Supported types are: %s", FLOW_STORAGE_TYPE_CONFIG,
                    storageInterfaceFactory.getLoggableTypeIds()
                )
            )
        );
    }

    @Singleton
    public LogDataStoreInterfaceFactory logDataStoreInterfaceFactory(final PluginRegistry pluginRegistry,
        final ApplicationContext applicationContext) {
        return new LogDataStoreInterfaceFactory(pluginRegistry, validator, applicationContext);
    }

    @Singleton
    public LogDataStoreInterface logDataStore(final LogDataStoreInterfaceFactory logDataStoreInterfaceFactory) {
        ensureLogDataStoreAllowed();
        String pluginId = getLogDataStorePluginId(logDataStoreInterfaceFactory);
        return logDataStoreInterfaceFactory.make(pluginId, logsConfig.getLogConfig(pluginId));
    }

    /**
     * Guards the external log store, which is an Enterprise Edition feature; the Enterprise Edition
     * overrides this to allow it, gated by its license instead.
     */
    protected void ensureLogDataStoreAllowed() {
        logsConfig.type().ifPresent(type -> {
            throw new IllegalArgumentException(
                "Configuring an external log store ('%s=%s') requires Flow Enterprise Edition.".formatted(FLOW_LOGS_TYPE_CONFIG, type)
            );
        });
    }

    /**
     * Resolves the configured log data store type, falling back to {@code flow.repository.type} so
     * that existing installs (no {@code flow.logs.type}) keep storing logs in the main database.
     */
    public String getLogDataStorePluginId(LogDataStoreInterfaceFactory logDataStoreInterfaceFactory) {
        String type = logsConfig.type().orElse(repositoryConfiguration.type());
        if (type == null) {
            throw new FlowRuntimeException(
                String.format(
                    "No log store configured through the application property '%s' (nor a fallback '%s'). Supported types are: %s",
                    FLOW_LOGS_TYPE_CONFIG, "flow.repository.type", logDataStoreInterfaceFactory.getLoggableTypeIds()
                )
            );
        }
        return type;
    }

    @ConfigurationProperties("flow")
    public record StorageConfig(
        @Nullable
        @MapFormat(keyFormat = StringConvention.CAMEL_CASE, transformation = MapFormat.MapTransformation.NESTED) Map<String, Object> storage) {

        /**
         * Returns the configuration for the configured storage.
         *
         * @return the configuration.
         */
        @SuppressWarnings("unchecked")
        public Map<String, Object> getStorageConfig(String type) {
            return (Map<String, Object>) storage.get(StringConvention.CAMEL_CASE.format(type));
        }
    }
}
