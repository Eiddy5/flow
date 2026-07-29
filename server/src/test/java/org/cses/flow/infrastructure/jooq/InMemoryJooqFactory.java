package org.cses.flow.infrastructure.jooq;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.x9.jooq.JOOQ;
import org.x9.jooq.intf.JooqRunnable;
import org.x9.jooq.intf.JooqRunnableResult;
import org.cses.flow.infrastructure.memory.InMemoryTransactionManager;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;

/**
 * Test-only JOOQ transaction boundary for Flow memory repositories.
 *
 * <p>The PAAS JOOQ factory only creates a bean for a configured data source.
 * Memory mode deliberately has no data source, but command and query execution
 * still share the same JOOQ-shaped boundary.</p>
 */
@Factory
@Requires(property = "flow.memory.enabled", value = "true")
@Requires(missingBeans = DataSource.class)
public class InMemoryJooqFactory {

    private final InMemoryTransactionManager transactionManager;

    public InMemoryJooqFactory(
        InMemoryTransactionManager transactionManager
    ) {
        this.transactionManager = transactionManager;
    }

    @Singleton
    JOOQ jooq() {
        DSLContext dsl = DSL.using(SQLDialect.POSTGRES);
        return new JOOQ(dsl.configuration(), null) {
            @Override
            public <T> T get(JooqRunnableResult<T> runnable) {
                return readTransaction(runnable, dsl);
            }

            @Override
            public <T> List<T> read(
                JooqRunnableResult<List<T>> runnable
            ) {
                return readTransaction(runnable, dsl);
            }

            @Override
            public <T> T runReturn(JooqRunnableResult<T> runnable) {
                return writeTransaction(runnable, dsl);
            }

            @Override
            public void run(JooqRunnable runnable) {
                try {
                    transactionManager.write(() -> runnable.run(dsl));
                } catch (SQLException exception) {
                    throw new IllegalStateException(
                        "Memory JOOQ operation failed",
                        exception
                    );
                }
            }
        };
    }

    private <T> T readTransaction(
        JooqRunnableResult<T> runnable,
        DSLContext dsl
    ) {
        try {
            return transactionManager.read(() -> runnable.run(dsl));
        } catch (SQLException exception) {
            throw new IllegalStateException(
                "Memory JOOQ operation failed",
                exception
            );
        }
    }

    private <T> T writeTransaction(
        JooqRunnableResult<T> runnable,
        DSLContext dsl
    ) {
        try {
            return transactionManager.write(() -> runnable.run(dsl));
        } catch (SQLException exception) {
            throw new IllegalStateException(
                "Memory JOOQ operation failed",
                exception
            );
        }
    }
}
