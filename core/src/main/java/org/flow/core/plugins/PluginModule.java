package org.flow.core.plugins;

import java.io.Serial;

import com.fasterxml.jackson.databind.module.SimpleModule;

import org.flow.core.app.AppPluginInterface;
import org.flow.core.models.assets.Asset;
import org.flow.core.models.assets.AssetExporter;
import org.flow.core.models.tasks.ExecutableTask;
import org.flow.core.models.tasks.Task;
import org.flow.core.models.tasks.logs.LogExporter;
import org.flow.core.models.tasks.runners.TaskRunner;
import org.flow.core.models.triggers.AbstractTrigger;
import org.flow.core.plugins.serdes.AssetDeserializer;
import org.flow.core.plugins.serdes.PluginDeserializer;
import org.flow.core.secret.SecretPluginInterface;
import org.flow.core.storages.StorageInterface;

/**
 * Jackson module for registering the {@link PluginDeserializer} for
 * all supported plugin type.
 */
@SuppressWarnings("this-escape")
public class PluginModule extends SimpleModule {
    @Serial
    private static final long serialVersionUID = 1L;

    public static final String NAME = "flow-plugin";

    /**
     * Creates a new {@link PluginModule} instance.
     */
    public PluginModule() {
        super(NAME);
        addDeserializer(ExecutableTask.class, new PluginDeserializer<>());
        addDeserializer(Task.class, new PluginDeserializer<>());
        addDeserializer(AbstractTrigger.class, new PluginDeserializer<>());
        addDeserializer(TaskRunner.class, new PluginDeserializer<>());
        addDeserializer(StorageInterface.class, new PluginDeserializer<>());
        addDeserializer(SecretPluginInterface.class, new PluginDeserializer<>());
        addDeserializer(AppPluginInterface.class, new PluginDeserializer<>());
        addDeserializer(LogExporter.class, new PluginDeserializer<>());
        addDeserializer(Asset.class, new AssetDeserializer());
        addDeserializer(AssetExporter.class, new PluginDeserializer<>());
    }
}
