package org.cses.flow.core.domains.triggers;

import org.cses.flow.core.plugins.Plugin;
import java.util.Map;

/** Flow 内独立生效的启动条件契约。 */
public interface Trigger extends Plugin {
    /**
     * @return 系统分配的触发器身份 */
    String id();
    /**
     * @return Flow 内唯一、跨版本稳定的配置名称 */
    String key();
    /**
     * @return true 表示不接收新的触发 */
    boolean disabled();
    /**
     * @return 只读的启动参数配置 */
    Map<String, Object> inputs();
    /** 校验公共配置与具体类型的条件，非法时抛出 IllegalArgumentException。 */
    void validate();
}
