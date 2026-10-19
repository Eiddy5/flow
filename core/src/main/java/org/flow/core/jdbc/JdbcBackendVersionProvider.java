package org.flow.core.jdbc;

import org.flow.core.services.BackendVersionProvider;
import org.flow.jdbc.JooqDSLContextWrapper;
import org.jooq.impl.DSL;
import lombok.extern.slf4j.Slf4j;

/**
 * 在 jOOQ 事务上下文中按需读取数据库版本，并缓存成功读取的结果。
 */
@Slf4j
public class JdbcBackendVersionProvider implements BackendVersionProvider {
    private JooqDSLContextWrapper dslContextWrapper;
    private String type;
    private Category category;
    private volatile String cachedVersion;

    /**
     * 创建指定后端类型和类别的数据库版本提供者。
     *
     * @param dslContextWrapper 提供数据库事务上下文的包装器
     * @param type 后端类型
     * @param category 后端类别
     */
    public JdbcBackendVersionProvider(JooqDSLContextWrapper dslContextWrapper, String type, Category category) {
        this.dslContextWrapper = dslContextWrapper;
        this.type = type;
        this.category = category;
    }

    /**
     * 返回当前数据库后端的类型。
     *
     * @return 后端类型
     */
    @Override
    public String type() {
        return type;
    }

    /**
     * 返回当前数据库后端所属的类别。
     *
     * @return 后端类别
     */
    @Override
    public Category category() {
        return category;
    }

    /**
     * 返回已缓存的数据库版本，或在事务中读取并缓存版本；读取失败后允许下次重试。
     *
     * @return 数据库产品名称与版本；读取失败时返回 {@code null}
     */
    @Override
    public String getVersion() {
        if (cachedVersion != null) {
            return cachedVersion;
        }

        try {
            cachedVersion = dslContextWrapper.transactionResult(configuration -> DSL.using(configuration).connectionResult(conn -> {
                var meta = conn.getMetaData();
                return meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion();
            }));
        } catch (Exception e) {
            log.debug("Failed to retrieve database version", e);
            return null;
        }

        return cachedVersion;
    }
}
