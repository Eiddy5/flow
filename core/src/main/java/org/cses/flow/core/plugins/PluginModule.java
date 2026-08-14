package org.cses.flow.core.plugins;

import com.fasterxml.jackson.databind.module.SimpleModule;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.tasks.Task;

import java.io.Serial;

/**
 * Installs strict polymorphic binding for supported plugin capabilities.
 */
@Singleton
public final class PluginModule extends SimpleModule {

    @Serial
    private static final long serialVersionUID = 1L;

    public PluginModule(PluginRegistry registry) {
        super("flow-plugin");
        addDeserializer(Task.class, new PluginDeserializer<>(registry));
    }
}
