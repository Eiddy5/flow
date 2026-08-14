package org.cses.flow.core.plugins;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable source example declared by one registered plugin.
 */
public record PluginExample(
    String title,
    List<String> code,
    String lang,
    boolean full
) {

    public PluginExample {
        title = title == null || title.isBlank() ? "" : title.trim();
        Objects.requireNonNull(code, "Plugin example code");
        if (code.isEmpty()) {
            throw new IllegalArgumentException(
                "Plugin example code must not be empty"
            );
        }
        for (String source : code) {
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException(
                    "Plugin example code must not contain blank sources"
                );
            }
        }
        code = List.copyOf(code);
        if (lang == null || lang.isBlank()) {
            throw new IllegalArgumentException(
                "Plugin example language must not be blank"
            );
        }
        lang = lang.trim().toLowerCase(Locale.ROOT);
    }
}
