package org.cses.flow.core.plugins;

import com.fasterxml.jackson.databind.module.SimpleModule;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.tasks.Task;

import java.io.Serial;

import static java.util.Objects.requireNonNull;

/**
 * Installs strict polymorphic binding for supported plugin capabilities.
 */
@Singleton
public final class PluginModule extends SimpleModule {

    @Serial
    private static final long serialVersionUID = 1L;
    private PluginRegistry registry;

    public PluginModule(PluginRegistry registry) {
        this(registry, false);
    }

    private PluginModule(
        PluginRegistry registry,
        boolean sourceDefinition
    ) {
        super("flow-plugin");
        this.registry = requireNonNull(registry, "Plugin registry");
        addDeserializer(
            Task.class,
            new PluginDeserializer<>(registry, sourceDefinition)
        );
    }

    public PluginModule sourceDefinitions() {
        return new PluginModule(registry, true);
    }
}
