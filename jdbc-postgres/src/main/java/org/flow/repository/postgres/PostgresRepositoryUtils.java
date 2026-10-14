package org.flow.repository.postgres;

import java.sql.Timestamp;

import org.jooq.Field;
import org.jooq.impl.DSL;


public final class PostgresRepositoryUtils {
    private PostgresRepositoryUtils() {
        // utility class pattern
    }

    /**
     * Renders {@code dateField} as a UTC wall-clock timestamp, for SQL-side date-part extraction.
     * <p>
     * Postgres date columns are {@code TIMESTAMPTZ}, which stores an absolute instant. Casting one to
     * {@code timestamp} resolves it in the session timezone, and pgjdbc sets the session timezone
     * from the JVM default — so a plain cast yields local, not UTC, date parts. {@code AT TIME ZONE
     * 'UTC'} pins the conversion instead of inheriting it from the connection.
     */
    public static Field<Timestamp> utcTimestampField(String dateField) {
        return DSL.field("({0} AT TIME ZONE 'UTC')", Timestamp.class, DSL.field(DSL.quotedName(dateField)));
    }

}
