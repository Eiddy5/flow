package org.flow.core.services.session;

import java.util.Map;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** 验证 Session 基础设施的开关由宿主提供。 */
class HostSessionConfigurationTest {
    /** 检查宿主未设置开关时，Flow 不向应用环境写入该属性。 */
    @Test
    void leavesSessionInfrastructureUnset() {
        try (var context = ApplicationContext.builder().deduceEnvironment(false)
            .environmentPropertySource(false).enableDefaultPropertySources(false).build()) {
            assertTrue(context.getEnvironment().start().getProperty("paas.session.enabled", Boolean.class).isEmpty());
        }
    }

    /**
     * 检查应用环境采用宿主显式提供的 Session 基础设施开关。
     * @param enabled true 表示宿主启用，false 表示宿主禁用
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void preservesTheHostInfrastructureChoice(boolean enabled) {
        try (var context = ApplicationContext.builder().deduceEnvironment(false)
            .environmentPropertySource(false).enableDefaultPropertySources(false)
            .properties(Map.of("paas.session.enabled", enabled)).build()) {
            assertEquals(enabled, context.getEnvironment().start().getRequiredProperty("paas.session.enabled", Boolean.class));
        }
    }
}
