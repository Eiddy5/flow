package org.flow.queue.jdbc;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.env.Environment;
import jakarta.inject.Named;
import org.flow.jdbc.JdbcTableConfig;
import org.flow.gen.flow.Tables;

/** 注册本模块拥有的生成表，并应用宿主配置的物理表名。 */
@Factory
@org.flow.queue.jdbc.JdbcQueueEnabled
public class QueueJdbcTableConfigsFactory {

    /**
     * 根据宿主配置创建本模块的表绑定，字段类型始终来自 gen。
     *
     * @param environment 宿主环境，用于读取可选的物理表名；不能为 null
     * @return 持有生成表类型的配置，不创建数据库表
     */
    @Bean
    @Named("queues")
    public JdbcTableConfig queues(Environment environment) {
        return JdbcTableConfig.configured("queues", JdbcQueueItem.class, Tables.QUEUES, environment);
    }
}
