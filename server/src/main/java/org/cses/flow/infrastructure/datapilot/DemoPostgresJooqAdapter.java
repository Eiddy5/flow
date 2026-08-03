package org.cses.flow.infrastructure.datapilot;

import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.x9.jooq.JOOQ;
import org.x9.jooq.intf.JooqRunnable;
import org.x9.jooq.intf.JooqRunnableResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

/**
 * Standalone transaction boundary for the user-facing demo environment.
 *
 * <p>Normal environments continue to use the platform-managed DataPilot
 * connection. Demo mode uses one short-lived PostgreSQL connection per
 * operation so the complete Core path can run without Consul.</p>
 */
@Primary
@Singleton
@Requires(property = "flow.demo.enabled", value = "true")
@Requires(
    property = "flow.memory.enabled",
    value = "false",
    defaultValue = "false"
)
public final class DemoPostgresJooqAdapter extends JOOQ {

    private final String url;
    private final String user;
    private final String password;

    public DemoPostgresJooqAdapter(
        @Value("${flow.demo.postgres.url}") String url,
        @Value("${flow.demo.postgres.user}") String user,
        @Value("${flow.demo.postgres.password}") String password
    ) {
        super(DSL.using(SQLDialect.POSTGRES).configuration(), null);
        this.url = requireText(url, "Demo PostgreSQL URL");
        this.user = requireText(user, "Demo PostgreSQL user");
        this.password = password == null ? "" : password;
    }

    @Override
    public <T> T get(JooqRunnableResult<T> runnable) {
        return transaction(false, runnable);
    }

    @Override
    public <T> List<T> read(
        JooqRunnableResult<List<T>> runnable
    ) {
        return transaction(false, runnable);
    }

    @Override
    public <T> T runReturn(JooqRunnableResult<T> runnable) {
        return transaction(true, runnable);
    }

    @Override
    public void run(JooqRunnable runnable) {
        transaction(true, dsl -> {
            runnable.run(dsl);
            return null;
        });
    }

    private <T> T transaction(
        boolean commit,
        JooqRunnableResult<T> runnable
    ) {
        try (Connection connection = DriverManager.getConnection(
            url,
            user,
            password
        )) {
            connection.setAutoCommit(false);
            try {
                T result = runnable.run(
                    DSL.using(connection, SQLDialect.POSTGRES)
                );
                if (commit) {
                    connection.commit();
                } else {
                    connection.rollback();
                }
                return result;
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw new IllegalStateException(
                    "Demo PostgreSQL operation failed",
                    exception
                );
            } catch (RuntimeException | Error failure) {
                rollback(connection, failure);
                throw failure;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                "Cannot access the demo PostgreSQL database",
                exception
            );
        }
    }

    private static void rollback(
        Connection connection,
        Throwable failure
    ) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                field + " must not be blank"
            );
        }
        return value.trim();
    }
}
