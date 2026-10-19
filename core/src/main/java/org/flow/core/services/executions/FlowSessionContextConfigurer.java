package org.flow.core.services.executions;

import java.util.Map;

import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.ApplicationContextConfigurer;
import io.micronaut.context.annotation.ContextConfigurer;
import io.micronaut.context.env.PropertySource;

/** 引入宿主 Session 合同时，不自动启动 PaaS 的认证过滤器、缓存和消费者。 */
@ContextConfigurer
public class FlowSessionContextConfigurer implements ApplicationContextConfigurer {
    /** 提供最低优先级默认值；宿主已配置的 Session 基础设施开关保持优先。 */
    @Override
    public void configure(ApplicationContextBuilder builder) {
        builder.propertySources(PropertySource.of("flow-session-runtime-defaults",
            Map.of("paas.session.enabled", false), Integer.MIN_VALUE));
    }
}
