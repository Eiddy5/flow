package org.flow.core.services.session;

import java.util.Map;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 验证 Session 基础设施默认关闭且宿主配置优先。 */
class FlowSessionContextConfigurerTest {
    /** 通过真实配置 SPI 加载默认值，不启动数据库或认证 Bean。 */
    @Test
    void disablesSessionInfrastructureByDefault() {
        try (var context = ApplicationContext.builder().deduceEnvironment(false)
            .environmentPropertySource(false).enableDefaultPropertySources(false).build()) {
            var environment = context.getEnvironment().start();
            assertFalse(environment.getRequiredProperty("paas.session.enabled", Boolean.class));
            assertFalse(context.isRunning());
            assertTrue(environment.getPropertySources().stream()
                .anyMatch(source -> source.getName().equals("flow-session-runtime-defaults")
                    && source.getOrder() == Integer.MIN_VALUE));
        }
    }

    /** 宿主显式启用 Session 基础设施时不被 Flow 默认配置覆盖。 */
    @Test
    void preservesTheHostInfrastructureChoice() {
        try (var context = ApplicationContext.builder().deduceEnvironment(false)
            .environmentPropertySource(false).enableDefaultPropertySources(false)
            .properties(Map.of("paas.session.enabled", true)).build()) {
            assertTrue(context.getEnvironment().start().getRequiredProperty("paas.session.enabled", Boolean.class));
            assertFalse(context.isRunning());
        }
    }
}
