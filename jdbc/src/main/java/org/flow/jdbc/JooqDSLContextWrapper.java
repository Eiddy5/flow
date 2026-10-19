package org.flow.jdbc;

import java.io.Serial;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Predicate;

import javax.sql.DataSource;
import jakarta.inject.Named;

import org.jooq.ConnectionProvider;
import org.jooq.DSLContext;
import org.jooq.TransactionalCallable;
import org.jooq.TransactionalRunnable;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConnectionProvider;
import org.jooq.impl.DefaultTransactionProvider;

import dev.failsafe.Failsafe;
import dev.failsafe.FailsafeExecutor;
import dev.failsafe.Fallback;
import dev.failsafe.RetryPolicy;
import dev.failsafe.event.ExecutionAttemptedEvent;

import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class JooqDSLContextWrapper {
    private static DeadlockPredicate deadlockPredicate = new DeadlockPredicate();
    private static RetryPolicy<Object> retryPolicy = RetryPolicy.builder()
        .handleIf(deadlockPredicate::test)
        .withDelay(50, 1000, ChronoUnit.MILLIS)
        .withMaxAttempts(-1)
        .withMaxDuration(Duration.ofSeconds(60))
        .onFailure(event -> log.warn("Stop retry, elapsed {} and {} attempts",
            event.getElapsedTime().truncatedTo(ChronoUnit.SECONDS), event.getAttemptCount(), event.getException()))
        .onRetry(event -> log.info("Retrying, elapsed {} and {} attempts",
            event.getElapsedTime().truncatedTo(ChronoUnit.SECONDS), event.getAttemptCount()))
        .build();

    private DSLContext dslContext;
    private DataSource rawDataSource;
    private FailsafeExecutor<Object> deadlockRetryer;

    /**
     * 将事务绑定到 flow 数据源，复用随机间隔且最多持续 60 秒的死锁重试策略。
     * @param dslContext flow 数据源的 PostgreSQL DSL 上下文，不能为 null
     * @param dataSource flow 连接池，不能为 null；独立事务从其原始数据源获取新连接
     * @throws IllegalArgumentException 数据库方言不是 PostgreSQL 时抛出
     */
    @Inject
    public JooqDSLContextWrapper(@Named("flow") DSLContext dslContext, @Named("flow") DataSource dataSource) {
        this(dslContext, dataSource, retryPolicy);
    }

    /**
     * 使用指定重试策略创建事务包装器，允许数据库技术测试限制重试次数和等待时间。
     * @param dslContext PostgreSQL DSL 上下文，不能为 null
     * @param dataSource 用于独立事务的连接池，不能为 null
     * @param transactionRetryPolicy 待复用的重试策略，不能为 null；只应处理可重试数据库故障
     * @throws IllegalArgumentException 数据库方言不是 PostgreSQL 时抛出
     */
    JooqDSLContextWrapper(DSLContext dslContext, DataSource dataSource, RetryPolicy<Object> transactionRetryPolicy) {
        if (dslContext.dialect().family() != org.jooq.SQLDialect.POSTGRES) {
            throw new IllegalArgumentException("Only PostgreSQL is supported by the JDBC backend");
        }
        this.dslContext = dslContext;
        // Unwrap any Micronaut Data AOP proxy: the wrapped DataSource hands back the current
        // thread's transaction-bound connection instead of a new one.
        this.rawDataSource = DelegatingDataSource.unwrapDataSource(dataSource);
        this.deadlockRetryer = Failsafe.with(
            Fallback.<Object>builderOfException(TransactionRetryFailedException::new)
                .handleIf(deadlockPredicate::test).build(),
            transactionRetryPolicy
        );
    }

    /**
     * 在当前数据源事务中执行回调；只对死锁或序列化冲突按策略重试。
     * @param transactional 要执行的数据库回调，不能为 null；死锁时可能重复执行
     * @throws TransactionRetryFailedException 重试耗尽时抛出，保留最后一次失败原因
     * @throws RuntimeException 非重试故障按原异常传播
     */
    public void transaction(TransactionalRunnable transactional) {
        deadlockRetryer.<Void>get(
            () ->
            {
                dslContext.transaction(transactional);
                return null;
            }
        );
    }

    /**
     * 在当前数据源事务中执行回调并返回结果；只对死锁或序列化冲突按策略重试。
     * @param transactional 要执行的数据库回调，不能为 null；死锁时可能重复执行
     * @param <T> 回调返回值的类型
     * @return 本次成功事务返回的原结果，可为 null
     * @throws TransactionRetryFailedException 重试耗尽时抛出，保留最后一次失败原因
     * @throws RuntimeException 非重试故障按原异常传播
     */
    public <T> T transactionResult(TransactionalCallable<T> transactional) {
        return deadlockRetryer.get(
            () -> dslContext.transactionResult(transactional)
        );
    }

    /**
     * 从原始连接池获取独立连接执行并提交事务，不加入当前线程已有事务。
     * 调用期间可能额外占用一个连接，提交结果在返回前对其他连接可见。
     * @param transactional 要执行的数据库回调，不能为 null；死锁时可能重复执行
     * @throws TransactionRetryFailedException 重试耗尽时抛出，保留最后一次失败原因
     * @throws DataAccessException 独立连接获取或关闭失败时抛出
     * @throws RuntimeException 非重试故障按原异常传播
     */
    public void requireNewTransaction(TransactionalRunnable transactional) {
        deadlockRetryer.<Void>get(
            () ->
            {
                try (Connection connection = rawDataSource.getConnection()) {
                    // Same configuration (dialect, settings, execute listeners), but jOOQ-managed
                    // transactions on this connection instead of the thread-bound ones.
                    ConnectionProvider connectionProvider = new DefaultConnectionProvider(connection);
                    DSL.using(
                        dslContext.configuration()
                            .derive(connectionProvider)
                            .derive(new DefaultTransactionProvider(connectionProvider))
                    )
                        .transaction(transactional);
                } catch (SQLException e) {
                    throw new DataAccessException("Unable to run a transaction on a new connection", e);
                }
                return null;
            }
        );
    }

    /** 数据库重试耗尽的失败信息，不依赖引擎的任务重试模型。 */
    @Getter
    public static class TransactionRetryFailedException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        private int attemptCount;
        private Duration elapsedTime;

        /**
         * 根据最后一次重试事件保存失败原因、累计次数和已耗时。
         * @param event 重试耗尽事件，不能为 null
         */
        private TransactionRetryFailedException(ExecutionAttemptedEvent<?> event) {
            super("Stop retry, attempts " + event.getAttemptCount() + " elapsed after "
                + event.getElapsedTime().getSeconds() + " seconds", event.getLastException());
            this.attemptCount = event.getAttemptCount();
            this.elapsedTime = event.getElapsedTime();
        }
    }

    /** 检查数据库异常链中的死锁或序列化冲突。 */
    static class DeadlockPredicate implements Predicate<Throwable> {
        @Override
        public boolean test(Throwable e) {
            // Walk the whole exception graph, not just the cause chain:
            // - the thrown exception itself can already be the SQLException;
            // - once Postgres aborts a transaction after a deadlock, a later statement in the same
            //   failed attempt surfaces a secondary "current transaction is aborted" exception that
            //   wraps the original deadlock one level deeper;
            // - rollback errors can be attached as suppressed exceptions;
            // - drivers chain secondary SQLExceptions on getNextException(), which is neither the
            //   cause nor a suppressed exception.
            // Track visited throwables by identity to stop on a cyclic or diamond-shaped graph.
            Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            Deque<Throwable> pending = new ArrayDeque<>();
            pending.add(e);

            while (!pending.isEmpty()) {
                Throwable current = pending.poll();
                if (!seen.add(current)) {
                    continue;
                }

                if (isDeadlockOrLockTimeout(current)) {
                    return true;
                }

                if (current.getCause() != null) {
                    pending.add(current.getCause());
                }
                Collections.addAll(pending, current.getSuppressed());
                // JDBC chains secondary failures on their own list rather than as causes
                if (current instanceof SQLException sqlException && sqlException.getNextException() != null) {
                    pending.add(sqlException.getNextException());
                }
            }
            return false;
        }

        private static boolean isDeadlockOrLockTimeout(Throwable cause) {
            if (!(cause instanceof SQLException sqlException)) {
                return false;
            }

            return
            // standard deadlock
            "40001".equals(sqlException.getSQLState()) ||
            // postgres deadlock
                "40P01".equals(sqlException.getSQLState());
        }
    }
}
