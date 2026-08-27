package org.cses.flow.infrastructure.jooq;

import io.micronaut.context.BeanContext;
import org.jooq.DSLContext;
import org.jooq.codegen.ext.mapping.JooqExtRegistry;
import org.jooq.codegen.ext.runtime.ExtConfiguration;
import org.jooq.impl.DSL;
import org.paas.common.util.BeanContextUtil;

import java.lang.reflect.Proxy;

/**
 * Builds the same JOOQ type-mapping configuration used by Flow in production.
 *
 * <p>The direct JDBC tests do not start Micronaut, while the extension provider
 * resolves its registry through {@link BeanContextUtil}. The minimal context
 * bridge below supplies only that registry and leaves an existing application
 * context untouched.</p>
 */
public final class FlowJooqTestConfiguration {

    private FlowJooqTestConfiguration() {
    }

    public static DSLContext configure(DSLContext dsl) {
        ensureBeanContext();
        return DSL.using(ExtConfiguration.enhance(dsl.configuration()));
    }

    private static synchronized void ensureBeanContext() {
        if (BeanContextUtil.beanContext != null) {
            try {
                if (BeanContextUtil.beanContext.getBean(
                    JooqExtRegistry.class
                ) != null) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // A test may have left a closed Micronaut context behind.
            }
        }
        BeanContextUtil.beanContext = (BeanContext) Proxy.newProxyInstance(
            BeanContext.class.getClassLoader(),
            new Class<?>[]{BeanContext.class},
            (proxy, method, args) -> {
                if ("getBean".equals(method.getName())
                    && args != null
                    && args.length > 0
                    && args[0] == JooqExtRegistry.class) {
                    return JooqExtRegistry.getInstance();
                }
                return null;
            }
        );
    }
}
