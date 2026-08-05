package org.cses.flow.controller.plugins;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import org.cses.flow.controller.plugins.PluginModels.PluginDetailsView;
import org.cses.flow.controller.plugins.PluginModels.RegisteredPluginView;
import org.cses.flow.core.services.plugins.PluginService;

import java.util.List;

/**
 * HTTP adapter for the global read-only plugin catalog.
 */
@Controller("/api/plugins")
public final class PluginController {

    private final PluginService pluginService;

    public PluginController(PluginService pluginService) {
        this.pluginService = pluginService;
    }

    @Get
    public List<RegisteredPluginView> plugins() {
        return pluginService.plugins().stream()
            .map(RegisteredPluginView::from)
            .toList();
    }

    @Get("/{canonicalType}")
    public PluginDetailsView plugin(
        @PathVariable String canonicalType
    ) {
        return PluginDetailsView.from(
            pluginService.plugin(canonicalType)
        );
    }
}
