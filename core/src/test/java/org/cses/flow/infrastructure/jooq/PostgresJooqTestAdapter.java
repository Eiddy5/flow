package org.cses.flow.infrastructure.jooq;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.cses.flow.executor.commands.ExecutorCommand;
import org.x9.jooq.JOOQ;
import org.x9.jooq.intf.JooqRunnable;
import org.x9.jooq.intf.JooqRunnableResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.flow.gen.flow.Tables.EXECUTIONS;
import static org.flow.gen.flow.Tables.FLOW_DRAFTS;
import static org.flow.gen.flow.Tables.FLOW_TASKS;
import static org.flow.gen.flow.Tables.FLOWS;
import static org.flow.gen.flow.Tables.QUEUES;
import static org.flow.gen.flow.Tables.TASK_RUNS;

/**
 * Test transaction boundary backed by the PostgreSQL UC database.
 */
public final class PostgresJooqTestAdapter extends JOOQ {

    private final String url;
    private final String user;
    private final String password;
    private final boolean cleanupEnabled;

    private PostgresJooqTestAdapter(
        String url,
        String user,
        String password,
        boolean cleanupEnabled
    ) {
        super(DSL.using(SQLDialect.POSTGRES).configuration(), null);
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
                .where(QUEUES.QUEUE_NAME.eq(
                    ExecutorCommand.QUEUE_NAME
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
            dsl.deleteFrom(FLOW_DRAFTS)
                .where(FLOW_DRAFTS.COMPANY_ID.eq(companyId))
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
