package org.flow.core.contexts;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 防止宿主 Context 关闭后插件解析仍访问已销毁的 Bean 容器。 */
class FlowContextLifecycleTest {
    /** 销毁当前 Context 后不再向调用方返回失效的实例。 */
    @Test
    void releasesTheDestroyedContext() {
        var context = new FlowContext.Initializer(mock(ApplicationContext.class), mock(Environment.class));
        assertSame(context, FlowContext.getContext());
        context.releaseContext();
        assertThrows(IllegalStateException.class, FlowContext::getContext);
    }

    /** 旧 Context 延迟销毁不能清除新宿主的全局实例。 */
    @Test
    void doesNotReleaseANewerContext() {
        var previous = new FlowContext.Initializer(mock(ApplicationContext.class), mock(Environment.class));
        var current = new FlowContext.Initializer(mock(ApplicationContext.class), mock(Environment.class));
        try {
            previous.releaseContext();
            assertSame(current, FlowContext.getContext());
        } finally {
            current.releaseContext();
        }
    }
}
