package org.flow.executor.jdbc;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.env.Environment;
import jakarta.inject.Named;
import org.flow.jdbc.JdbcTableConfig;
import org.flow.gen.flow.Tables;
import org.flow.core.runners.ConcurrencyLimit;
import org.flow.core.models.executions.Execution;
import org.flow.core.runners.ExecutionDelay;
import org.flow.core.runners.ExecutionQueued;
import org.flow.core.models.flows.sla.SLAMonitor;

/** 注册本模块拥有的生成表，并应用宿主配置的物理表名。 */
@Factory
@org.flow.core.jdbc.runner.JdbcRepositoryEnabled
public class ExecutorJdbcTableConfigsFactory {

    /**
     * 根据宿主配置创建本模块的表绑定，字段类型始终来自 gen。
     *
     * @param environment 宿主环境，用于读取可选的物理表名；不能为 null
     * @return 持有生成表类型的配置，不创建数据库表
     */
    @Bean
    @Named("executions")
    public JdbcTableConfig executions(Environment environment) {
        return JdbcTableConfig.configured("executions", Execution.class, Tables.EXECUTIONS, environment);
    }

    /**
     * 根据宿主配置创建本模块的表绑定，字段类型始终来自 gen。
     *
     * @param environment 宿主环境，用于读取可选的物理表名；不能为 null
     * @return 持有生成表类型的配置，不创建数据库表
     */
    @Bean
    @Named("executordelayed")
    public JdbcTableConfig executorDelayed(Environment environment) {
        return JdbcTableConfig.configured("executordelayed", ExecutionDelay.class, Tables.EXECUTORDELAYED, environment);
    }

    /**
     * 根据宿主配置创建本模块的表绑定，字段类型始终来自 gen。
     *
     * @param environment 宿主环境，用于读取可选的物理表名；不能为 null
     * @return 持有生成表类型的配置，不创建数据库表
     */
    @Bean
    @Named("executionqueued")
    public JdbcTableConfig executionQueued(Environment environment) {
        return JdbcTableConfig.configured("executionqueued", ExecutionQueued.class, Tables.EXECUTION_QUEUED, environment);
    }

    /**
     * 根据宿主配置创建本模块的表绑定，字段类型始终来自 gen。
     *
     * @param environment 宿主环境，用于读取可选的物理表名；不能为 null
     * @return 持有生成表类型的配置，不创建数据库表
     */
    @Bean
    @Named("slamonitor")
    public JdbcTableConfig slaMonitor(Environment environment) {
        return JdbcTableConfig.configured("slamonitor", SLAMonitor.class, Tables.SLA_MONITOR, environment);
    }

    /**
     * 根据宿主配置创建本模块的表绑定，字段类型始终来自 gen。
     *
     * @param environment 宿主环境，用于读取可选的物理表名；不能为 null
     * @return 持有生成表类型的配置，不创建数据库表
     */
    @Bean
    @Named("concurrencylimit")
    public JdbcTableConfig concurrencyLimit(Environment environment) {
        return JdbcTableConfig.configured("concurrencylimit", ConcurrencyLimit.class, Tables.CONCURRENCY_LIMIT, environment);
    }
}
