package org.flow.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JooqExecuteListenerFactoryTest {
    /**
     * 验证直接使用 Micrometer 后保留指标前缀、原说明、公共标签和脱敏 SQL 标签。
     * @throws SQLException 创建连接替身时声明
     */
    @Test
    void keepsPrefixCommonTagsAndSanitizedSql() throws SQLException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            registry.config().commonTags("environment", "unit", "server_type", "WORKER");
            ExecuteListener listener = listener(registry, "engine", 0);
            ExecuteContext context = context("SELECT value FROM executions WHERE state_current IN (?, ?, ?)", ExecuteContext.BatchMode.NONE);

            listener.executeStart(context);
            listener.executeEnd(context);

            Timer timer = registry.get("engine.jdbc.query.duration").timer();
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.getId().getDescription()).isEqualTo(
                "Duration of database queries, including row fetch, tagged with sanitized SQL (IN-lists collapsed, identifiers and sort columns redacted). Only queries above the 'flow.jdbc.metrics.query-duration-threshold-ms' threshold (default 10ms) are monitored");
            assertThat(timer.getId().getTag("environment")).isEqualTo("unit");
            assertThat(timer.getId().getTag("server_type")).isEqualTo("WORKER");
            assertThat(timer.getId().getTag("sql")).isEqualTo("SELECT value FROM executions WHERE state_current IN (?)");
        } finally {
            registry.close();
        }
    }

    /**
     * 验证没有前缀配置时使用原始指标名，空字符串前缀仍保留原有句点语义。
     * @throws SQLException 创建连接替身时声明
     */
    @Test
    void preservesAbsentAndEmptyPrefixBehavior() throws SQLException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            ExecuteContext context = context("SELECT 1", ExecuteContext.BatchMode.NONE);
            ExecuteListener absentPrefix = listener(registry, null, 0);
            absentPrefix.executeStart(context);
            absentPrefix.executeEnd(context);
            ExecuteListener emptyPrefix = listener(registry, "", 0);
            emptyPrefix.executeStart(context);
            emptyPrefix.executeEnd(context);

            assertThat(registry.get("jdbc.query.duration").timer().count()).isEqualTo(1);
            assertThat(registry.get(".jdbc.query.duration").timer().count()).isEqualTo(1);
        } finally {
            registry.close();
        }
    }

    /**
     * 验证多语句批处理和没有 SQL 的上下文都不创建查询计时指标。
     * @throws SQLException 创建连接替身时声明
     */
    @Test
    void excludesMultipleStatementBatchesAndMissingSql() throws SQLException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            ExecuteListener listener = listener(registry, null, 0);
            ExecuteContext batch = context("SELECT 1", ExecuteContext.BatchMode.MULTIPLE);
            listener.executeStart(batch);
            listener.executeEnd(batch);
            ExecuteContext noSql = context(null, ExecuteContext.BatchMode.NONE);
            listener.executeStart(noSql);
            listener.executeEnd(noSql);

            assertThat(registry.getMeters()).isEmpty();
        } finally {
            registry.close();
        }
    }

    /**
     * 验证快速查询未达到配置阈值时不创建查询计时指标。
     * @throws SQLException 创建连接替身时声明
     */
    @Test
    void excludesQueriesBelowConfiguredThreshold() throws SQLException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            ExecuteListener listener = listener(registry, null, 60_000);
            ExecuteContext context = context("SELECT 1", ExecuteContext.BatchMode.NONE);
            listener.executeStart(context);
            listener.executeEnd(context);

            assertThat(registry.getMeters()).isEmpty();
        } finally {
            registry.close();
        }
    }

    /**
     * 为指定 Registry、前缀和毫秒阈值创建查询监听器。
     * @param registry 测试使用的指标 Registry，不能为 null
     * @param prefix 指标前缀；null 表示不添加前缀
     * @param thresholdMillis 查询计时阈值，单位毫秒
     * @return 新建的监听器
     */
    private ExecuteListener listener(SimpleMeterRegistry registry, String prefix, long thresholdMillis) {
        return new JooqExecuteListenerFactory()
            .jooqConfiguration(registry, new JdbcTableConfigs.MetricConfig(thresholdMillis), prefix)
            .provide();
    }

    /**
     * 创建使用 READ COMMITTED 连接的查询上下文替身，不连接数据库。
     * @param sql 当前 SQL 文本，允许 null 以验证无 SQL 的回调
     * @param batchMode 当前批处理模式，不能为 null
     * @return 新建的查询上下文替身
     * @throws SQLException 配置连接隔离级别替身时声明
     */
    private ExecuteContext context(String sql, ExecuteContext.BatchMode batchMode) throws SQLException {
        Connection connection = mock(Connection.class);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        ExecuteContext context = mock(ExecuteContext.class);
        when(context.connection()).thenReturn(connection);
        when(context.sql()).thenReturn(sql);
        when(context.batchMode()).thenReturn(batchMode);
        return context;
    }
}
