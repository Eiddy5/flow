package org.flow.repository.postgres;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.TimeZone;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record2;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.flow.gen.flow.Tables.METRICS;

/**
 * Pins the UTC semantics of {@link PostgresRepositoryUtils#utcTimestampField(Field)}.
 * <p>
 * Postgres date columns are {@code TIMESTAMPTZ}. A plain {@code cast(... as timestamp)} resolves in
 * the session timezone, which pgjdbc sets from the JVM default — so date parts extracted that way
 * follow the host's zone. Since {@code AbstractJdbcRepository#getDate} reassembles those parts in
 * UTC, they have to be extracted in UTC too, whatever the connection's session timezone is.
 * <p>
 * Uses a raw connection rather than the injected datasource because the JVM default zone has to be
 * set before the connection is opened for pgjdbc to pick it up.
 */
class PostgresUtcTimestampFieldTest {
    private static final String URL = System.getenv().getOrDefault("FLOW_UPSTREAM_TEST_URL", "jdbc:postgresql://localhost:5432/flow_unit");
    private static final String USER = System.getenv().getOrDefault("FLOW_UPSTREAM_TEST_USER", "flow");
    private static final String PASSWORD = System.getenv().getOrDefault("FLOW_UPSTREAM_TEST_PASSWORD", "flow");

    /** Zones whose offset from UTC is non-zero, in both directions, one of them non-integral. */
    private static final List<String> NON_UTC_ZONES = List.of("Asia/Kolkata", "Europe/Berlin", "America/Los_Angeles");

    private static final Instant INSTANT = Instant.parse("2026-07-29T14:30:00Z");

    @Test
    void shouldExtractUtcDatePartsRegardlessOfSessionTimeZone() throws Exception {
        TimeZone original = TimeZone.getDefault();

        try {
            for (String zone : NON_UTC_ZONES) {
                // Given a JVM default zone that pgjdbc will propagate to the session timezone
                TimeZone.setDefault(TimeZone.getTimeZone(zone));

                try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
                    DSLContext context = DSL.using(connection, SQLDialect.POSTGRES);

                    // ... and a TIMESTAMPTZ column holding a known absolute instant
                    context.execute("DROP TABLE IF EXISTS utc_timestamp_field_test");
                    context.execute("CREATE TABLE utc_timestamp_field_test (\"timestamp\" TIMESTAMPTZ NOT NULL)");
                    context.execute("INSERT INTO utc_timestamp_field_test VALUES ({0}::timestamptz)", DSL.val(INSTANT.toString()));

                    // When extracting date parts the way groupByFields does
                    var table = METRICS.rename("utc_timestamp_field_test");
                    Field<java.sql.Timestamp> timestamp = PostgresRepositoryUtils.utcTimestampField(table.TIMESTAMP);
                    Record2<Integer, Integer> parts = context
                        .select(DSL.day(timestamp), DSL.hour(timestamp))
                        .from(table)
                        .fetchSingle();

                    // Then they are the UTC wall-clock parts, not the session-zone ones
                    assertThat(parts.value1()).as("day, JVM zone %s", zone).isEqualTo(29);
                    assertThat(parts.value2()).as("hour, JVM zone %s", zone).isEqualTo(14);

                    context.execute("DROP TABLE utc_timestamp_field_test");
                }
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void shouldRenderAtTimeZoneUtcRatherThanAPlainCast() {
        // Given the grouping timestamp expression for Postgres
        Field<java.sql.Timestamp> timestamp = PostgresRepositoryUtils.utcTimestampField(METRICS.TIMESTAMP);

        // When rendering it
        String sql = DSL.using(SQLDialect.POSTGRES).render(DSL.hour(timestamp));

        // Then the conversion is pinned to UTC instead of inheriting the session timezone
        assertThat(sql).contains("AT TIME ZONE 'UTC'");
    }
}
