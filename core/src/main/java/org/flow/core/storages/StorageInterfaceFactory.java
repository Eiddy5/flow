package org.flow.core.storages;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.flow.core.exceptions.FlowRuntimeException;
import org.flow.core.plugins.AbstractPluginInterfaceFactory;
import org.flow.core.plugins.PluginRegistry;
import org.flow.core.plugins.RegisteredPlugin;

import jakarta.validation.Validator;

/**
 * Factory class for constructing {@link StorageInterface} objects.
 */
public class StorageInterfaceFactory extends AbstractPluginInterfaceFactory<StorageInterface> {

    public static final String FLOW_STORAGE_TYPE_CONFIG = "flow.storage.type";

    public StorageInterfaceFactory(final PluginRegistry pluginRegistry,
        final Validator validator) {
        super(pluginRegistry, validator);
    }

    /**
     * Factory method for constructing and validating new {@link StorageInterface} of the given type with the given configuration.
     *
     * @param identifier The ID of the storage. cannot be {@code null}.
     * @param pluginConfiguration The configuration of the storage. cannot be {@code null}.
     * @return a new {@link StorageInterface}.
     * @throws FlowRuntimeException if no storage can be found.
     */
    public StorageInterface make(final StorageConfiguration storageConfiguration,
        final String identifier,
        final Map<String, Object> pluginConfiguration) {

        StorageInterface plugin = resolve(identifier, pluginConfiguration);

        try {
            plugin = init(storageConfiguration, plugin);
        } catch (IOException e) {
            throw new FlowRuntimeException(
                String.format(
                    "Failed to initialize storage '%s'. Error: %s", identifier, e.getMessage()
                ), e
            );
        }
        return plugin;
    }

    protected StorageInterface init(final StorageConfiguration storageConfiguration,
        final StorageInterface plugin) throws IOException {
        plugin.init();
        return plugin;
    }

    @Override
    protected String typeProperty() {
        return FLOW_STORAGE_TYPE_CONFIG;
    }

    @Override
    protected String lookupDisplayName() {
        return "storage interface";
    }

    @Override
    protected String configDisplayName() {
        return "storage";
    }

    @Override
    protected List<? extends Class<?>> pluginClasses(final RegisteredPlugin plugin) {
        return plugin.getStorages();
    }
}
