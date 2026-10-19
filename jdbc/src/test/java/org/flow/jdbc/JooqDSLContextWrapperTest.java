package org.flow.jdbc;

import java.sql.SQLException;
import javax.sql.DataSource;

import dev.failsafe.RetryPolicy;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.TransactionalCallable;
import org.jooq.TransactionalRunnable;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JooqDSLContextWrapperTest {
    /** 验证不支持的方言在申请连接之前被拒绝。 */
    @Test
    void rejectsUnsupportedDialectBeforeAcquiringAConnection() {
        DataSource source = mock(DataSource.class);
        assertThatThrownBy(() -> new JooqDSLContextWrapper(DSL.using(SQLDialect.DEFAULT), source))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Only PostgreSQL");
        verifyNoInteractions(source);
    }

    /** 验证创建 PostgreSQL 事务包装器本身不申请连接。 */
    @Test
    void acceptsPostgresWithoutOpeningAConnection() {
        DataSource source = mock(DataSource.class);
        assertThatCode(() -> new JooqDSLContextWrapper(DSL.using(SQLDialect.POSTGRES), source))
            .doesNotThrowAnyException();
        verifyNoInteractions(source);
    }

    /** 验证死锁后重新执行事务并返回随后成功的原始结果。 */
    @Test
    void retriesDeadlockAndReturnsSuccessfulTransactionResult() {
        DSLContext context = postgresContext();
        DataAccessException deadlock = new DataAccessException("deadlock", new SQLException("deadlock", "40P01"));
        Object result = new Object();
        when(context.transactionResult(any(TransactionalCallable.class))).thenThrow(deadlock).thenReturn(result);

        Object actual = wrapper(context).transactionResult(configuration -> result);
        assertThat(actual).isSameAs(result);
        verify(context, times(2)).transactionResult(any(TransactionalCallable.class));
    }

    /** 验证无返回值事务在序列化冲突后重新执行，成功后不再重试。 */
    @Test
    void retriesVoidTransactionAfterSerializationConflict() {
        DSLContext context = postgresContext();
        DataAccessException conflict = new DataAccessException("conflict", new SQLException("serialization", "40001"));
        doThrow(conflict).doNothing().when(context).transaction(any(TransactionalRunnable.class));

        wrapper(context).transaction(configuration -> { });

        verify(context, times(2)).transaction(any(TransactionalRunnable.class));
    }

    /** 验证非死锁异常仅执行一次，并保留原异常实例，不增加 Failsafe 包装。 */
    @Test
    void propagatesNonDeadlockFailureWithoutWrappingOrRetrying() {
        DSLContext context = postgresContext();
        DataAccessException failure = new DataAccessException("syntax", new SQLException("syntax", "42601"));
        when(context.transactionResult(any(TransactionalCallable.class))).thenThrow(failure);

        assertThatThrownBy(() -> wrapper(context).transactionResult(configuration -> "unused"))
            .isSameAs(failure);
        verify(context).transactionResult(any(TransactionalCallable.class));
    }

    /** 验证重试耗尽携带最后一次失败、累计次数和非负耗时，不依赖引擎异常类型。 */
    @Test
    void preservesLastCauseAndRetryDetailsWhenAttemptsAreExhausted() {
        DSLContext context = postgresContext();
        DataAccessException first = new DataAccessException("first", new SQLException("deadlock", "40P01"));
        DataAccessException last = new DataAccessException("last", new SQLException("deadlock", "40P01"));
        when(context.transactionResult(any(TransactionalCallable.class))).thenThrow(first).thenThrow(last);

        assertThatThrownBy(() -> wrapper(context).transactionResult(configuration -> "unused"))
            .isInstanceOfSatisfying(JooqDSLContextWrapper.TransactionRetryFailedException.class, failure -> {
                assertThat(failure.getCause()).isSameAs(last);
                assertThat(failure.getAttemptCount()).isEqualTo(2);
                assertThat(failure.getElapsedTime().isNegative()).isFalse();
                assertThat(failure).hasMessageStartingWith("Stop retry, attempts 2 elapsed after ");
            });
        verify(context, times(2)).transactionResult(any(TransactionalCallable.class));
    }

    /**
     * 验证独立事务获取连接失败时保留 JDBC 原因，非死锁不重复获取连接。
     * @throws SQLException Mockito 配置数据源受检异常时声明
     */
    @Test
    void preservesIndependentConnectionFailureCause() throws SQLException {
        DataSource source = mock(DataSource.class);
        SQLException failure = new SQLException("connection refused", "08001");
        when(source.getConnection()).thenThrow(failure);
        JooqDSLContextWrapper wrapper = new JooqDSLContextWrapper(postgresContext(), source);

        assertThatThrownBy(() -> wrapper.requireNewTransaction(configuration -> { }))
            .isInstanceOf(DataAccessException.class).hasCause(failure);
        verify(source).getConnection();
    }

    /**
     * 创建方言为 PostgreSQL 的 DSL 替身，不连接数据库。
     * @return 每次调用新建的 DSL 替身
     */
    private DSLContext postgresContext() {
        DSLContext context = mock(DSLContext.class);
        when(context.dialect()).thenReturn(SQLDialect.POSTGRES);
        return context;
    }

    /**
     * 使用最多两次且无需等待的死锁策略创建测试事务包装器。
     * @param context 测试使用的 DSL 替身，不能为 null
     * @return 绑定该替身的新包装器
     */
    private JooqDSLContextWrapper wrapper(DSLContext context) {
        RetryPolicy<Object> policy = RetryPolicy.builder()
            .handleIf(new JooqDSLContextWrapper.DeadlockPredicate()::test)
            .withMaxAttempts(2)
            .build();
        return new JooqDSLContextWrapper(context, mock(DataSource.class), policy);
    }
}
