package org.flow.http.plugins;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.Introspected;

public final class PluginModels {
    private PluginModels() {
    }

    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Metadata(String type, String category, String title, String description, String group) {
    }

    @Introspected
    public record Details(Metadata metadata, Map<String, Object> schema, List<Example> examples, List<Output> outputs) {
    }

    @Introspected
    public record Example(String title, String lang, boolean full, List<String> code) {
    }

    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Output(String name, String type, String description) {
    }
}
