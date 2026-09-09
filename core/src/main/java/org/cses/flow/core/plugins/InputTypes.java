package org.cses.flow.core.plugins;

import com.fasterxml.jackson.annotation.JsonTypeName;
import org.cses.flow.core.domains.flows.Input;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Input names and read aliases shared by Jackson versions and plugin discovery. */
public class InputTypes {
    private Map<String, Class<? extends Input>> bindings;

    /**
     * Validates concrete types and builds annotation names plus legacy class-name aliases.
     * @param types nonnull collection of distinct, annotated Input classes, not modified
     * @throws IllegalStateException if a class or a name is invalid or duplicated
     */
    public InputTypes(Collection<Class<?>> types) {
        Map<String, Class<? extends Input>> result = new LinkedHashMap<>();
        for (Class<?> type : Objects.requireNonNull(types, "Input types")) {
            String name = name(type);
            Class<? extends Input> input = type.asSubclass(Input.class);
            register(result, name, input);
            if (!name.equals(type.getCanonicalName())) {
                register(result, type.getCanonicalName(), input);
            }
        }
        bindings = Collections.unmodifiableMap(result);
    }

    /**
     * Reads every compiler-generated index visible to the application loader.
     * @param loader nonnull application class loader
     * @return distinct indexed classes without initializing or constructing them
     * @throws IllegalStateException if an index cannot be read or a listed class is absent
     */
    public static Collection<Class<?>> discover(ClassLoader loader) {
        LinkedHashSet<Class<?>> types = new LinkedHashSet<>();
        try {
            var resources = loader.getResources("META-INF/flow/inputs");
            while (resources.hasMoreElements()) {
                try (var reader = new BufferedReader(new InputStreamReader(
                    resources.nextElement().openStream(), StandardCharsets.UTF_8))) {
                    for (String line; (line = reader.readLine()) != null;) {
                        if (!line.isBlank()) {
                            types.add(Class.forName(line.trim(), false, loader));
                        }
                    }
                }
            }
            return Collections.unmodifiableSet(types);
        } catch (IOException | ClassNotFoundException exception) {
            throw new IllegalStateException("Cannot load Flow Input index", exception);
        }
    }

    /**
     * Reads the sole definition name owned by a concrete Input class.
     * @param type public concrete Input class with its own nonblank JsonTypeName
     * @return exact annotation value, never normalized
     * @throws IllegalStateException if the class or annotation is invalid
     */
    public static String name(Class<?> type) {
        if (type == null || !Input.class.isAssignableFrom(type)
            || !Modifier.isPublic(type.getModifiers()) || Modifier.isAbstract(type.getModifiers())
            || type.getCanonicalName() == null || type.getPackageName().isBlank()) {
            throw new IllegalStateException("Input must be a public concrete class: " + type);
        }
        JsonTypeName annotation = type.getDeclaredAnnotation(JsonTypeName.class);
        if (annotation == null || annotation.value().isBlank()
            || !annotation.value().equals(annotation.value().trim())) {
            throw new IllegalStateException("Input requires a nonblank @JsonTypeName: " + type.getName());
        }
        return annotation.value();
    }

    /** @return immutable names and class-name aliases accepted when reading */
    public Map<String, Class<? extends Input>> bindings() {
        return bindings;
    }

    /**
     * Preserves the historical whitespace and case tolerance of built-in codes only.
     * @param name unrecognized textual type identifier
     * @return registered built-in class or null when no legacy code matches
     */
    public Class<? extends Input> legacyType(String name) {
        Class<? extends Input> type = bindings.get(name.trim().toUpperCase(Locale.ROOT));
        return type != null && type.getPackageName().equals(Input.class.getPackageName() + ".inputs")
            ? type : null;
    }

    /**
     * Adds one unique name, failing instead of letting Jackson silently overwrite it.
     * @param bindings mutable destination
     * @param name exact nonblank definition name or read alias
     * @param type registered concrete Input class
     * @throws IllegalStateException if the name already belongs to a registration
     */
    private static void register(Map<String, Class<? extends Input>> bindings,
                                 String name, Class<? extends Input> type) {
        Class<?> previous = bindings.putIfAbsent(name, type);
        if (previous != null) {
            throw new IllegalStateException("Duplicate Input type '" + name + "': "
                + previous.getName() + " and " + type.getName());
        }
    }
}
