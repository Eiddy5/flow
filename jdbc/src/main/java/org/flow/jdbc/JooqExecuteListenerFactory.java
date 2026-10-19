package org.flow.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.ExecuteListenerProvider;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micronaut.context.annotation.EachBean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Factory
public class JooqExecuteListenerFactory {
    private static final String QUERY_DURATION = "jdbc.query.duration";
    private static final String QUERY_DURATION_DESCRIPTION = "Duration of database queries, including row fetch, tagged with sanitized SQL (IN-lists collapsed, identifiers and sort columns redacted). Only queries above the 'flow.jdbc.metrics.query-duration-threshold-ms' threshold (default 10ms) are monitored";

    /**
     * 为每个数据源创建 SQL 监听器，记录超过阈值的查询并保留配置前缀及 Registry 的公共标签。
     * @param meterRegistry 当前应用使用的指标 Registry，不能为 null
     * @param metricConfig 查询计时阈值配置，不能为 null
     * @param metricPrefix 指标名前缀；null 时不加前缀，非 null 时在前缀后追加句点
     * @return 为每次请求创建独立查询计时状态的监听器提供者
     */
    @EachBean(DataSource.class)
    public ExecuteListenerProvider jooqConfiguration(MeterRegistry meterRegistry, JdbcTableConfigs.MetricConfig metricConfig,
        @Nullable @Value("${flow.metrics.prefix}") String metricPrefix) {
        String metricName = (metricPrefix != null ? metricPrefix + "." : "") + QUERY_DURATION;
        return new ExecuteListenerProvider() {
            /**
             * 创建具有独立开始时间的监听器；连接隔离级别检查状态在监听器之间共享。
             * @return 新建的查询监听器
             */
            @Override
            public @NotNull ExecuteListener provide() {
                return new ExecuteListener() {
                    private static AtomicBoolean connectionChecked = new AtomicBoolean(false);

                    private long startTime;

                    /**
                     * 记录查询开始时间，并在首次查询时校验连接使用 READ COMMITTED 隔离级别。
                     * @param ctx 当前查询上下文，不能为 null
                     * @throws IllegalStateException 首次检查发现连接隔离级别不符合要求时抛出
                     */
                    @Override
                    public void executeStart(ExecuteContext ctx) {
                        startTime = System.nanoTime();

                        // PostgreSQL queues require READ COMMITTED isolation,
                        // our queue system didn't work correctly otherwise.
                        if (!connectionChecked.getAndSet(true)) {
                            try {
                                if (ctx.connection().getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
                                    throw new IllegalStateException("Isolation level must be READ COMMITTED");
                                }
                            } catch (SQLException e) {
                                // silently ignore any exception here
                            }
                        }
                    }

                    /**
                     * 记录超过阈值且不属于多语句批处理的 SQL 耗时，标签使用脱敏 SQL。
                     * @param ctx 当前查询上下文，不能为 null；无 SQL 时不记录指标
                     */
                    @Override
                    public void executeEnd(ExecuteContext ctx) {
                        long duration = System.nanoTime() - startTime;

                        // Record a timer for the sanitized SQL for queries above the threshold.
                        // IN-lists collapsed and aliases/sort columns redacted, so the tag stays bounded.
                        // Exclude batch queries as they will be expanded without parameters.
                        if (duration > metricConfig.queryDurationThresholdMs() * 1_000_000 && ctx.batchMode() != ExecuteContext.BatchMode.MULTIPLE && ctx.sql() != null) {
                            Timer.builder(metricName)
                                .description(QUERY_DURATION_DESCRIPTION)
                                .tag("sql", JdbcSqlSanitizer.sanitize(ctx.sql()))
                                .register(meterRegistry)
                                .record(duration, TimeUnit.NANOSECONDS);
                        }

                        // logged unsanitized, deliberately: the sanitized tag above loses which column a slow query sorted/filtered by
                        if (log.isTraceEnabled()) {
                            log.trace("[Duration: {}] [Rows: {}] [Query: {}]", duration, ctx.rows(), ctx.query());
                        } else if (log.isDebugEnabled()) {
                            log.debug("[Duration: {}] [Rows: {}] [Query: {}]", duration, ctx.rows(), ctx.sql());
                        }
                    }
                };
            }
        };
    }
}
