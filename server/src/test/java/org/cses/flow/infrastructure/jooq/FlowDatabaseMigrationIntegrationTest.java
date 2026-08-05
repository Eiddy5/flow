package org.cses.flow.infrastructure.jooq;

import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import org.cses.flow.core.services.flows.FlowService;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;
import org.x9.jooq.JOOQ;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class FlowDatabaseMigrationIntegrationTest {

    @Test
    void migratesAndUsesOnlyTheNamedFlowDataSource() throws SQLException {
        String url = System.getenv("FLOW_POSTGRES_TEST_URL");
        String defaultUrl = System.getenv(
            "FLOW_POSTGRES_DEFAULT_TEST_URL"
        );
        assumeTrue(
            url != null && !url.isBlank()
                && defaultUrl != null && !defaultUrl.isBlank(),
            "FLOW_POSTGRES_TEST_URL and "
                + "FLOW_POSTGRES_DEFAULT_TEST_URL are required"
        );
        String username = environment(
            "FLOW_POSTGRES_TEST_USER",
            "flow"
        );
        String password = environment(
            "FLOW_POSTGRES_TEST_PASSWORD",
            "flow"
        );
        String defaultUsername = environment(
            "FLOW_POSTGRES_DEFAULT_TEST_USER",
            username
        );
        String defaultPassword = environment(
            "FLOW_POSTGRES_DEFAULT_TEST_PASSWORD",
            password
        );
        String companyId = "flow-migration-integration";

        assertHostDatabaseHasNoFlowTables(
            defaultUrl,
            defaultUsername,
            defaultPassword
        );
        removeTenantDraftIfSchemaExists(
            url,
            username,
            password,
            companyId
        );

        try (ApplicationContext context = ApplicationContext.run(
            properties(
                url,
                username,
                password,
                defaultUrl,
                defaultUsername,
                defaultPassword
            )
        )) {
            assertNotNull(context.getBean(
                JOOQ.class,
                Qualifiers.byName(FlowDatabase.DATA_SOURCE_NAME)
            ));

            FlowService flowService = context.getBean(FlowService.class);
            var draft = flowService.saveDraft(
                session(companyId),
                """
                key: migration-integration
                description: Flow named datasource integration
                tasks:
                  - key: start
                    type: org.cses.flow.extensions.tasks.AutomaticTask
                """
            );
            assertEquals(
                draft.id(),
                flowService.draft(session(companyId), draft.id())
                    .orElseThrow()
                    .id()
            );
        }

        try (Connection connection = DriverManager.getConnection(
            url,
            username,
            password
        )) {
            assertTrue(tableExists(connection, "flows"));
            assertTrue(tableExists(connection, "task_run"));
            assertTrue(tableExists(
                connection,
                FlowDatabase.SCHEMA_HISTORY_TABLE
            ));
            assertTrue(successfulMigrations(connection) > 0);
            assertEquals(1, tenantDrafts(connection, companyId));
            removeTenantDraft(connection, companyId);
        }
        assertHostDatabaseHasNoFlowTables(
            defaultUrl,
            defaultUsername,
            defaultPassword
        );

    }

    private static Map<String, Object> properties(
        String url,
        String username,
        String password,
        String defaultUrl,
        String defaultUsername,
        String defaultPassword
    ) {
        return Map.ofEntries(
            Map.entry("datasources.flow.url", url),
            Map.entry(
                "datasources.flow.driver-class-name",
                "org.postgresql.Driver"
            ),
            Map.entry("datasources.flow.username", username),
            Map.entry("datasources.flow.password", password),
            Map.entry("datasources.flow.maximum-pool-size", 2),
            Map.entry("jooq.datasources.flow.sql-dialect", "POSTGRES"),
            Map.entry("datasources.default.url", defaultUrl),
            Map.entry(
                "datasources.default.driver-class-name",
                "org.postgresql.Driver"
            ),
            Map.entry("datasources.default.username", defaultUsername),
            Map.entry("datasources.default.password", defaultPassword),
            Map.entry("datasources.default.maximum-pool-size", 2),
            Map.entry("jooq.datasources.default.sql-dialect", "POSTGRES"),
            Map.entry("flow.database.migration.enabled", true),
            Map.entry("jooq.send-event", false),
            Map.entry("micronaut.config-client.enabled", false),
            Map.entry("consul.client.registration.enabled", false),
            Map.entry("consul.client.config.enabled", false),
            Map.entry("grpc.server.enabled", false),
            Map.entry("thrift.server.enabled", false),
            Map.entry("pulsar.consumer.enabled", false)
        );
    }

    private static void assertHostDatabaseHasNoFlowTables(
        String url,
        String username,
        String password
    ) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
            url,
            username,
            password
        )) {
            assertFalse(tableExists(connection, "flows"));
            assertFalse(tableExists(connection, "task_run"));
            assertFalse(tableExists(
                connection,
                FlowDatabase.SCHEMA_HISTORY_TABLE
            ));
        }
    }

    private static void removeTenantDraftIfSchemaExists(
        String url,
        String username,
        String password,
        String companyId
    ) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
            url,
            username,
            password
        )) {
            if (tableExists(connection, "flow_drafts")) {
                removeTenantDraft(connection, companyId);
            }
        }
    }

    private static Session<User> session(String companyId) {
        User user = new User();
        user.setId("flow-migration-user");
        user.setCompanyId(companyId);
        Session<User> session = new Session<>();
        session.setUser(user);
        return session;
    }

    private static boolean tableExists(
        Connection connection,
        String table
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT to_regclass(?) IS NOT NULL"
        )) {
            statement.setString(1, "public." + table);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static int successfulMigrations(Connection connection)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT count(*) FROM flow_schema_history WHERE success"
        ); ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    private static int tenantDrafts(
        Connection connection,
        String companyId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT count(*) FROM flow_drafts WHERE company_id = ?"
        )) {
            statement.setString(1, companyId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static void removeTenantDraft(
        Connection connection,
        String companyId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM flow_drafts WHERE company_id = ?"
        )) {
            statement.setString(1, companyId);
            statement.executeUpdate();
        }
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
