package org.flow.http.plugins;

import java.util.List;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import org.paas.response.wrapper.annotation.SkipResponseWrap;

/** Publishes the Flow plugin catalog through the host's PaaS HTTP server. */
@Controller("/flow/plugins")
@Requires(property = "flow.http.plugins.enabled", value = "true")
@SkipResponseWrap
@ExecuteOn(TaskExecutors.BLOCKING)
public final class PluginController {
    private final PluginQueries plugins;

    public PluginController(PluginQueries plugins) {
        this.plugins = plugins;
    }

    /** Lists registered plugin types, optionally restricted to one category. */
    @Get
    public HttpResponse<List<PluginModels.Metadata>> list(@QueryValue @Nullable String category) {
        if (category != null && category.isBlank()) {
            return HttpResponse.badRequest();
        }
        return HttpResponse.ok(plugins.list(category));
    }

    /** Returns the configuration schema, examples and output fields for one canonical type. */
    @Get("/{type}")
    public HttpResponse<PluginModels.Details> detail(@PathVariable String type) {
        if (type.isBlank()) {
            return HttpResponse.badRequest();
        }
        return plugins.detail(type).map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    /** Lists the loaded versions of a registered canonical type. */
    @Get("/{type}/versions")
    public HttpResponse<List<String>> versions(@PathVariable String type) {
        if (type.isBlank()) {
            return HttpResponse.badRequest();
        }
        return plugins.versions(type).map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }
}
