package org.cses.flow.core.plugins;

import java.util.List;
import java.util.Objects;

/**
 * Description of one registered concrete plugin class.
 */
public record PluginMetadata<T extends Plugin>(
    Class<? extends T> type,
    Class<T> baseClass,
    String title,
    String description,
    List<PluginExample> examples,
    List<String> capabilities
) {

    public static <T extends Plugin> PluginMetadata<T> from(
        Class<? extends T> type,
        Class<T> baseClass,
        String title,
        String description,
        List<PluginExample> examples,
        List<String> capabilities
    ) {
        return new PluginMetadata<>(
            type,
            baseClass,
            title,
            description,
            examples,
            capabilities
        );
    }

    public static <T extends Plugin> PluginMetadata<T> from(
        Class<? extends T> type,
        Class<T> baseClass,
        String title,
        String description
    ) {
        return from(
            type,
            baseClass,
            title,
            description,
            List.of(),
            List.of()
        );
    }

    public PluginMetadata {
        Objects.requireNonNull(type, "Plugin class");
        Objects.requireNonNull(baseClass, "Plugin base class");
        if (!baseClass.isAssignableFrom(type)) {
            throw new IllegalArgumentException(
                type.getName() + " is not a " + baseClass.getName()
            );
        }
        if (type.getCanonicalName() == null
            || type.getCanonicalName().isBlank()) {
            throw new IllegalArgumentException(
                "Plugin class must have a canonical name: "
                    + type.getName()
            );
        }
        title = title == null || title.isBlank()
            ? type.getSimpleName()
            : title;
        description = description == null || description.isBlank()
            ? ""
            : description;
        Objects.requireNonNull(examples, "Plugin examples");
        examples = List.copyOf(examples);
        Objects.requireNonNull(capabilities, "Plugin capabilities");
        List<String> normalizedCapabilities = capabilities.stream()
            .map(capability -> capability == null ? "" : capability.trim())
            .toList();
        if (normalizedCapabilities.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException(
                "Plugin capabilities must not contain blank values"
            );
        }
        if (normalizedCapabilities.stream().distinct().count()
            != normalizedCapabilities.size()) {
            throw new IllegalArgumentException(
                "Plugin capabilities must not contain duplicates"
            );
        }
        capabilities = List.copyOf(normalizedCapabilities);
    }

    public PluginMetadata(
        Class<? extends T> type,
        Class<T> baseClass,
        String title,
        String description
    ) {
        this(type, baseClass, title, description, List.of(), List.of());
    }

    public String canonicalType() {
        return type.getCanonicalName();
    }

    public String packageName() {
        return type.getPackageName();
    }
}
