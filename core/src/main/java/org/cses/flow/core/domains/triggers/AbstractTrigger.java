package org.cses.flow.core.domains.triggers;

import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.Builder;
import org.cses.flow.core.utils.RequiredUtil;
import java.util.Map;

/** 只保存触发定义，调度游标、领取和重试不属于定义。 */
@SuperBuilder
@NoArgsConstructor
public abstract class AbstractTrigger implements Trigger {
    String id;
    String key;
    String description;
    boolean disabled;
    @Builder.Default
    Map<String, Object> inputs = Map.of();

    /**
     * @return 持久化身份 */
    public String id() { return id; }
    /**
     * @return 配置名称 */
    public String key() { return key; }
    /**
     * @return 是否禁用 */
    public boolean disabled() { return disabled; }
    /**
     * @return 不可修改的参数映射 */
    public Map<String, Object> inputs() { return Map.copyOf(inputs); }
    /**
     * @return 可空的说明 */
    public String description() { return description; }
    /**
     * 发布新版本时沿用同 key 的技术身份。
     *
     * @param previousId 非空的已有身份
     */
    public void reidentify(String previousId) { id = RequiredUtil.required(previousId, "Trigger id"); }
    /** 校验身份、名称和启动参数，非法时抛出 IllegalArgumentException。 */
    public void validate() {
        RequiredUtil.required(id, "Trigger id");
        RequiredUtil.required(key, "Trigger key");
        if (!key.equals(key.trim())) throw new IllegalArgumentException("Trigger key must be trimmed");
        if (inputs == null) throw new IllegalArgumentException("Trigger inputs must not be null");
        inputs.forEach((name, value) -> {
            RequiredUtil.required(name, "Trigger input key");
            if (value == null) throw new IllegalArgumentException("Trigger input value must not be null");
        });
    }
}
