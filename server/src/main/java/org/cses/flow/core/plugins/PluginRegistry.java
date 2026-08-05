package org.cses.flow.core.plugins;

import java.util.List;
import java.util.Optional;

/**
 * Read-only view of all plugins registered during application startup.
 */
public interface PluginRegistry {

    /**
     * Returns immutable plugin bundles in deterministic order.
     */
    List<RegisteredPlugin> plugins();

    /**
     * Finds metadata by the exact canonical plugin class name.
     */
    Optional<PluginMetadata<? extends Plugin>> findMetadata(String type);

    /**
     * Resolves an exact plugin class and checks the requested capability.
     */
    <P extends Plugin> Class<? extends P> resolve(
        String type,
        Class<P> expectedBaseClass
    );
}
