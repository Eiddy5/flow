package org.flow.scheduler.jdbc;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.env.Environment;
import jakarta.inject.Named;
import org.flow.jdbc.JdbcTableConfig;
import org.flow.gen.flow.Tables;
import org.flow.core.models.triggers.multipleflows.MultipleConditionWindow;
import org.flow.core.scheduler.model.TriggerState;

/** 注册本模块拥有的生成表，并应用宿主配置的物理表名。 */
@Factory
@org.flow.core.jdbc.runner.JdbcRepositoryEnabled
public class SchedulerJdbcTableConfigsFactory {

    /**
     * 根据宿主配置创建本模块的表绑定，字段类型始终来自 gen。
     *
     * @param environment 宿主环境，用于读取可选的物理表名；不能为 null
     * @return 持有生成表类型的配置，不创建数据库表
     */
    @Bean
    @Named("triggers")
    public JdbcTableConfig triggers(Environment environment) {
        return JdbcTableConfig.configured("triggers", TriggerState.class, Tables.TRIGGERS, environment);
    }

    /**
     * 根据宿主配置创建本模块的表绑定，字段类型始终来自 gen。
     *
     * @param environment 宿主环境，用于读取可选的物理表名；不能为 null
     * @return 持有生成表类型的配置，不创建数据库表
     */
    @Bean
    @Named("multipleconditions")
    public JdbcTableConfig multipleConditions(Environment environment) {
        return JdbcTableConfig.configured("multipleconditions", MultipleConditionWindow.class, Tables.MULTIPLECONDITIONS, environment);
    }
}
