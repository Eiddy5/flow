package org.flow.jdbc.migration;

import java.util.UUID;

import org.postgresql.ds.PGSimpleDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.flow.core.migration.MigrationScript;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link JdbcMigrationHistoryStore} using an isolated PostgreSQL schema.
 * No Micronaut context is needed — the raw-DataSource constructor is used directly.
 */
class JdbcMigrationHistoryStoreTest {

    private JdbcMigrationHistoryStore store;
    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(java.util.Objects.requireNonNull(System.getenv("FLOW_UPSTREAM_TEST_URL"),
            "Run through docs/harness/verify-flow.sh"));
        dataSource.setUser(System.getenv("FLOW_UPSTREAM_TEST_USER"));
        dataSource.setPassword(System.getenv("FLOW_UPSTREAM_TEST_PASSWORD"));
        schema = "history_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        dataSource.setCurrentSchema(schema);
        store = new JdbcMigrationHistoryStore(dataSource);
        store.bootstrapIfNeeded();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dataSource != null && schema != null) {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void bootstrapIfNeeded_createsTable() throws Exception {
        // Table must exist after bootstrap — hasAnyApplied() would throw if it didn't
        assertThatNoException().isThrownBy(() -> store.hasAnyApplied());
    }

    @Test
    void isApplied_returnsFalseForUnknownScript() throws Exception {
        // Given: empty history store
        // When / Then
        assertThat(store.isApplied("2.0")).isFalse();
    }

    @Test
    void markApplied_thenIsApplied_returnsTrue() throws Exception {
        // Given
        MigrationScript script = script("2.0", "checksum-2.0");

        // When
        store.markApplied(script, 42L);

        // Then
        assertThat(store.isApplied("2.0")).isTrue();
    }

    @Test
    void hasAnyApplied_returnsFalseWhenEmpty_trueAfterMark() throws Exception {
        // Given: fresh database
        assertThat(store.hasAnyApplied()).isFalse();

        // When
        store.markApplied(script("2.0", "checksum-2.0"), 10L);

        // Then
        assertThat(store.hasAnyApplied()).isTrue();
    }

    @Test
    void validateChecksum_throwsOnMismatch() throws Exception {
        // Given: script applied with original checksum
        store.markApplied(script("2.0", "original-checksum"), 10L);

        // When / Then: same script ID, different checksum
        assertThatThrownBy(() -> store.validateChecksum(script("2.0", "tampered-checksum")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Checksum mismatch");
    }

    @Test
    void validateChecksum_passesOnMatch() throws Exception {
        // Given: script applied with a checksum
        store.markApplied(script("2.0", "correct-checksum"), 10L);

        // When / Then: same checksum — no exception
        assertThatNoException().isThrownBy(() -> store.validateChecksum(script("2.0", "correct-checksum")));
    }

    @Test
    void updateChecksum_updatesAppliedScriptChecksum() throws Exception {
        // Given
        store.markApplied(script("2.0", "original-checksum"), 10L);

        // When
        store.updateChecksum(script("2.0", "current-checksum"));

        // Then
        assertThatNoException().isThrownBy(() -> store.validateChecksum(script("2.0", "current-checksum")));
        assertThatThrownBy(() -> store.validateChecksum(script("2.0", "original-checksum")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Checksum mismatch");
    }

    @Test
    void updateChecksum_rejectsUnknownScript() {
        // Given / When / Then
        assertThatThrownBy(() -> store.updateChecksum(script("missing", "checksum")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot repair checksum for unapplied migration [missing]");
    }

    @Test
    void shouldReturnFalseForLegacyUpgradeOnFreshInstall() throws Exception {
        // Given: fresh PostgreSQL schema with no flyway_schema_history table
        assertThat(store.detectLegacyUpgrade()).isFalse();
    }

    // --- Helpers ---

    private MigrationScript script(final String scriptId, final String checksum) {
        return new MigrationScript() {
            @Override
            public String scriptId() {
                return scriptId;
            }

            @Override
            public String description() {
                return "Migration " + scriptId;
            }

            @Override
            public String checksum() {
                return checksum;
            }

            @Override
            public void migrate() {
            }
        };
    }
}
