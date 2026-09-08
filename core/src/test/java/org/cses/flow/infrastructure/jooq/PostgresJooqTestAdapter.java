package org.cses.flow.infrastructure.jooq;

import com.zaxxer.hikari.util.DriverDataSource;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.cses.flow.executor.commands.ExecutionCommand;
import org.cses.flow.executor.ExecutorEvent;
import org.x9.jooq.JOOQ;
import org.x9.jooq.intf.JooqRunnable;
import org.x9.jooq.intf.JooqRunnableResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;

import static org.flow.gen.flow.Tables.EXECUTIONS;
import static org.flow.gen.flow.Tables.FLOW_TASKS;
import static org.flow.gen.flow.Tables.FLOWS;
import static org.flow.gen.flow.Tables.QUEUES;
import static org.flow.gen.flow.Tables.TASK_RUNS;

/**
 * PostgreSQL UC adapter with direct auto-commit access and explicit transaction helpers.
 */
public class PostgresJooqTestAdapter extends JOOQ {

    private String url;
    private String user;
    private String password;
    private boolean cleanupEnabled;

    /**
     * Configures direct SQL and explicit transaction helpers for the same test
     * database, using production Entry mappers without retaining a connection.
     *
     * @param url non-blank PostgreSQL JDBC URL selected by the test environment
     * @param user database login user; never used as a business actor
     * @param password database login password; not logged by this adapter
     * @param cleanupEnabled whether fixture close removes its own tenant data
     */
    private PostgresJooqTestAdapter(
        String url,
        String user,
        String password,
        boolean cleanupEnabled
    ) {
        super(
            FlowJooqTestConfiguration.configure(
                DSL.using(SQLDialect.POSTGRES)
            ).configuration(),
            new DriverDataSource(
                url, "org.postgresql.Driver", new Properties(), user, password
            )
        );
        this.url = url;
        this.user = user;
        this.password = password;
        this.cleanupEnabled = cleanupEnabled;
    }

    public static PostgresJooqTestAdapter fromEnvironment() {
        String url = System.getenv("FLOW_POSTGRES_TEST_URL");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                "FLOW_POSTGRES_TEST_URL is required for UC tests"
            );
        }
        return new PostgresJooqTestAdapter(
            url,
            System.getenv().getOrDefault(
                "FLOW_POSTGRES_TEST_USER",
                "flow"
            ),
            System.getenv().getOrDefault(
                "FLOW_POSTGRES_TEST_PASSWORD",
                "flow"
            ),
            Boolean.parseBoolean(System.getenv().getOrDefault(
                "FLOW_POSTGRES_TEST_CLEANUP",
                "false"
            ))
        );
    }

    public boolean cleanupEnabled() {
        return cleanupEnabled;
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

    public void removeTenant(String companyId) {
        transaction(true, dsl -> {
            dsl.deleteFrom(QUEUES)
                .where(QUEUES.QUEUE_NAME.in(
                    ExecutionCommand.QUEUE_NAME,
                    ExecutorEvent.QUEUE_NAME
                ))
                .and(DSL.field(
                    "{0} ->> 'companyId'",
                    String.class,
                    QUEUES.PAYLOAD
                ).eq(companyId))
                .execute();
            dsl.deleteFrom(TASK_RUNS)
                .where(TASK_RUNS.EXECUTION_ID.in(
                    dsl.select(EXECUTIONS.ID)
                        .from(EXECUTIONS)
                        .where(EXECUTIONS.COMPANY_ID.eq(companyId))
                ))
                .execute();
            dsl.deleteFrom(EXECUTIONS)
                .where(EXECUTIONS.COMPANY_ID.eq(companyId))
                .execute();
            dsl.deleteFrom(FLOW_TASKS)
                .where(FLOW_TASKS.COMPANY_ID.eq(companyId))
                .execute();
            dsl.deleteFrom(FLOWS)
                .where(FLOWS.COMPANY_ID.eq(companyId))
                .execute();
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
                var plainDsl = DSL.using(connection, SQLDialect.POSTGRES);
                T result = runnable.run(
                    FlowJooqTestConfiguration.configure(plainDsl)
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
                    "PostgreSQL UC operation failed",
                    exception
                );
            } catch (RuntimeException | Error failure) {
                rollback(connection, failure);
                throw failure;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                "Cannot access PostgreSQL UC database",
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
}
