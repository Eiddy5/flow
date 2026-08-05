package org.cses.flow.core.plugins;

import java.util.Objects;

/**
 * Description of one registered concrete plugin class.
 */
public record PluginMetadata<T extends Plugin>(
    Class<? extends T> type,
    Class<T> baseClass,
    String title,
    String description
) {

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
    }

    public String canonicalType() {
        return type.getCanonicalName();
    }
}
