package org.flow.indexer.jdbc.migration;

import java.util.Optional;

import io.micronaut.context.condition.ConditionContext;

/**
 * Resolves the effective JDBC log-store dialect for migration gating.
 *
 * <p>
 * The log store type is {@code flow.logs.type} when set, otherwise it falls back to
 * {@code flow.repository.type} (logs stored in the main database) — mirroring
 * {@code FlowBeansFactory.getLogDataStorePluginId}.
 *
 * <p>
 * Used by the log-table widening migrations so they run for the dialect that actually backs the log
 * store, whether a dedicated log repository is configured or logs use the current repository backend.
 */
public final class LogStoreTypeResolver {

    private LogStoreTypeResolver() {
    }

    /**
     * @return the effective log-store dialect ({@code flow.logs.type ?? flow.repository.type}), or empty when neither property is set.
     */
    public static Optional<String> effectiveType(final ConditionContext<?> context) {
        String type = context.getProperty("flow.logs.type", String.class)
            .orElseGet(() -> context.getProperty("flow.repository.type", String.class).orElse(null));

        if (type == null) {
            return Optional.empty();
        }

        return Optional.of(type.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * @return {@code true} when the effective log-store dialect equals the given dialect.
     */
    public static boolean matches(final ConditionContext<?> context, final String dialect) {
        return effectiveType(context).map(type -> type.equalsIgnoreCase(dialect)).orElse(false);
    }
}
