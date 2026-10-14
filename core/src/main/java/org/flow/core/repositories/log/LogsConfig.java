package org.flow.core.repositories.log;

import java.util.Map;
import java.util.Optional;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.convert.format.MapFormat;
import io.micronaut.core.naming.conventions.StringConvention;

/**
 * Configuration for the pluggable log data store, bound from {@code flow.logs.*}.
 * <p>
 * Mirrors {@code FlowBeansFactory.StorageConfig}: the {@code logs} map field under the
 * {@code flow} prefix captures the whole {@code flow.logs} subtree, so both the selector
 * ({@code flow.logs.type}) and the per-type sub-configuration ({@code flow.logs.<type>.*})
 * can be read from a single bean.
 */
@ConfigurationProperties("flow")
public record LogsConfig(
    @Nullable
    @MapFormat(keyFormat = StringConvention.CAMEL_CASE, transformation = MapFormat.MapTransformation.NESTED) Map<String, Object> logs) {

    /**
     * @return the configured {@code flow.logs.type}, if any.
     */
    public Optional<String> type() {
        return Optional.ofNullable(logs)
            .map(m -> m.get("type"))
            .map(Object::toString);
    }

    /**
     * Returns the per-type configuration sub-map for the configured log data store.
     *
     * @param type the resolved log data store type.
     * @return the configuration, or an empty map when none is provided.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getLogConfig(String type) {
        if (logs == null) {
            return Map.of();
        }
        Object config = logs.get(StringConvention.CAMEL_CASE.format(type));
        return config == null ? Map.of() : (Map<String, Object>) config;
    }
}
