package org.flow.indexer.jdbc;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.env.Environment;
import jakarta.inject.Named;
import org.flow.jdbc.JdbcTableConfig;
import org.flow.gen.flow.Tables;
import org.flow.core.models.executions.statistics.ExecutionStatistic;
import org.flow.core.models.executions.LogEntry;
import org.flow.core.models.executions.MetricEntry;

/** 注册本模块拥有的生成表，并应用宿主配置的物理表名。 */
@Factory
@org.flow.core.jdbc.runner.JdbcRepositoryEnabled
public class IndexerJdbcTableConfigsFactory {

    /**
     * 根据宿主配置创建本模块的表绑定，字段类型始终来自 gen。
     *
     * @param environment 宿主环境，用于读取可选的物理表名；不能为 null
     * @return 持有生成表类型的配置，不创建数据库表
     */
    @Bean
    @Named("logs")
    public JdbcTableConfig logs(Environment environment) {
        return JdbcTableConfig.configured("logs", LogEntry.class, Tables.LOGS, environment);
    }

    /**
     * 根据宿主配置创建本模块的表绑定，字段类型始终来自 gen。
     *
     * @param environment 宿主环境，用于读取可选的物理表名；不能为 null
     * @return 持有生成表类型的配置，不创建数据库表
     */
    @Bean
    @Named("metrics")
    public JdbcTableConfig metrics(Environment environment) {
        return JdbcTableConfig.configured("metrics", MetricEntry.class, Tables.METRICS, environment);
    }

    /**
     * 根据宿主配置创建本模块的表绑定，字段类型始终来自 gen。
     *
     * @param environment 宿主环境，用于读取可选的物理表名；不能为 null
     * @return 持有生成表类型的配置，不创建数据库表
     */
    @Bean
    @Named("executionstatistics")
    public JdbcTableConfig executionStatistics(Environment environment) {
        return JdbcTableConfig.configured("executionstatistics", ExecutionStatistic.class, Tables.EXECUTION_STATISTICS, environment);
    }
}
