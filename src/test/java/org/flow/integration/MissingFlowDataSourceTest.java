package org.flow.integration;

import java.util.Map;

import org.flow.jdbc.JooqDSLContextWrapper;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.exceptions.DependencyInjectionException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertThrows;

@MicronautTest(startApplication = false, transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MissingFlowDataSourceTest implements TestPropertyProvider {
    @Inject ApplicationContext context;

    /**
     * 仅提供调用应用默认数据源，验证引擎不能把它当作缺失的 flow 数据源。
     *
     * @return 只包含 default 数据源的测试配置
     * @throws IllegalStateException 未提供验证库 URL 时抛出
     */
    @Override
    public Map<String, String> getProperties() {
        String url = System.getenv("FLOW_CONTROL_URL");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("请配置 FLOW_CONTROL_URL");
        }
        return Map.of(
            "flow.migration.auto", "false",
            "flow.repository.type", "postgres",
            "flow.queue.type", "postgres",
            "datasources.default.url", url,
            "datasources.default.username", "flow",
            "datasources.default.password", "flow",
            "datasources.default.driver-class-name", "org.postgresql.Driver"
        );
    }

    /** 缺少具名 flow 数据源时装配失败，不使用现存 default 数据源。 */
    @Test
    void refusesFallbackToDefaultDatasource() {
        assertThrows(DependencyInjectionException.class, () -> context.getBean(JooqDSLContextWrapper.class));
        assertThrows(DependencyInjectionException.class, () -> context.getBean(org.flow.core.migration.MigrationHistoryStore.class));
        assertThrows(DependencyInjectionException.class, () -> context.getBean(org.flow.core.migration.MigrationLock.class));
    }
}
