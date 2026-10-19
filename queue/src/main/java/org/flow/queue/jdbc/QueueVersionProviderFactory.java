package org.flow.queue.jdbc;

import org.flow.core.jdbc.JdbcBackendVersionProvider;
import org.flow.core.services.BackendVersionProvider;
import org.flow.jdbc.JooqDSLContextWrapper;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

/**
 * 创建 JDBC 队列后端的版本提供者。
 */
@Factory
public class QueueVersionProviderFactory {
    /**
     * 创建按需读取并缓存数据库版本的队列版本提供者。
     *
     * @param dslContextWrapper 提供数据库事务上下文的包装器
     * @param environment 当前运行环境
     * @return 队列后端版本提供者
     */
    @Singleton
    @Named("jdbcQueueVersionProvider")
    @Requires(property = "flow.queue.type", value = "postgres")
    public BackendVersionProvider jdbcQueueVersionProvider(
        JooqDSLContextWrapper dslContextWrapper,
        Environment environment) {
        String type = environment.getProperty("flow.queue.type", String.class).orElse("jdbc");
        return new JdbcBackendVersionProvider(dslContextWrapper, type, BackendVersionProvider.Category.QUEUE);
    }
}
