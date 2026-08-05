package org.cses.flow.infrastructure.jooq;

/**
 * Stable integration contract for the database owned by the Flow library.
 */
public final class FlowDatabase {

    public static final String DATA_SOURCE_NAME = "flow";

    public static final String MIGRATION_LOCATION =
        "classpath:db/migration/flow";

    public static final String SCHEMA_HISTORY_TABLE =
        "flow_schema_history";

    private FlowDatabase() {
    }
}
