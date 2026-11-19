package org.flow.core.jdbc.postgres;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

import javax.sql.DataSource;

import io.micronaut.context.annotation.Value;
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

/** Initializes the PostgreSQL tables used by the Flow engine. */
@Singleton
@PostgresRepositoryEnabled
public class PostgresSchema {
    private static final String SQL_DIRECTORY = "/db/postgres/";

    private final DataSource dataSource;
    private final String schema;

    public PostgresSchema(@Named("flow") DataSource dataSource, @Value("${datasources.flow.schema:}") String schema) {
        this.dataSource = dataSource;
        this.schema = schema;
    }

    /**
     * Creates the Flow schema on the named flow datasource. Repeating initialization with the same DDL preserves its data.
     *
     * @throws IOException if a schema resource cannot be loaded
     * @throws SQLException if PostgreSQL cannot initialize the schema
     */
    public void initialize() throws IOException, SQLException {
        createTables(dataSource, schema, true, "logs");
    }

    /**
     * Creates the log table for a separately configured PostgreSQL log store.
     *
     * @param dataSource datasource used by the log store
     * @param table name of the log table in that datasource's current schema
     * @throws IOException if a schema resource cannot be loaded
     * @throws SQLException if PostgreSQL cannot initialize the log table
     */
    public static void initializeLogs(DataSource dataSource, String table) throws IOException, SQLException {
        createTables(dataSource, "", false, table);
    }

    private static void createTables(DataSource dataSource, String configuredSchema, boolean includeEngineTables, String logTable)
        throws IOException, SQLException {
        try (var connection = DelegatingDataSource.unwrapDataSource(dataSource).getConnection();
            var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                String currentSchema = connection.getSchema();
                String targetSchema = configuredSchema.isEmpty() ? currentSchema : configuredSchema;
                if (targetSchema == null) {
                    throw new SQLException("The Flow datasource has no current PostgreSQL schema");
                }

                try (var lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                    lock.setString(1, "org.flow.schema:" + targetSchema);
                    lock.execute();
                }
                String schemaIdentifier = statement.enquoteIdentifier(targetSchema, true);
                if (!targetSchema.equals(currentSchema)) {
                    statement.execute("CREATE SCHEMA IF NOT EXISTS " + schemaIdentifier);
                }
                statement.execute("SET LOCAL search_path TO " + schemaIdentifier);
                statement.execute(readSql("types.sql"));
                if (includeEngineTables) {
                    statement.execute(readSql("tables.sql"));
                }
                statement.execute(readSql("logs.sql").replace("${table}", logTable.replace("\"", "\"\"")));
                connection.commit();
            } catch (IOException | SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw e;
            }
        }
    }

    private static String readSql(String file) throws IOException {
        try (var input = PostgresSchema.class.getResourceAsStream(SQL_DIRECTORY + file)) {
            if (input == null) {
                throw new IOException("Missing PostgreSQL schema resource " + file);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
