package org.flow.core.jdbc;

import org.flow.core.services.BackendVersionProvider;
import org.flow.jdbc.JooqDSLContextWrapper;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

/**
 * 创建 JDBC 仓储后端的版本提供者。
 */
@Factory
public class JdbcVersionProviderFactory {

    /**
     * 创建按需读取并缓存数据库版本的仓储版本提供者。
     *
     * @param dslContextWrapper 提供数据库事务上下文的包装器
     * @param environment 当前运行环境
     * @return 仓储后端版本提供者
     */
    @Singleton
    @Named("jdbcRepositoryVersionProvider")
    @Requires(property = "flow.repository.type", value = "postgres")
    public BackendVersionProvider jdbcRepositoryVersionProvider(
        JooqDSLContextWrapper dslContextWrapper,
        Environment environment) {
        String type = environment.getProperty("flow.repository.type", String.class).orElse("jdbc");
        return new JdbcBackendVersionProvider(dslContextWrapper, type, BackendVersionProvider.Category.REPOSITORY);
    }
}
