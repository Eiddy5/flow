package org.cses.flow.core.domains.tasks.retries;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** ConstantRetry 的不可变策略定义。 */
@lombok.EqualsAndHashCode(callSuper = true)
public class ConstantRetry extends AbstractRetry {
    private long intervalMillis;

    /**
     * 校验并创建策略；毫秒间隔必须符合策略范围。
     * @param maxAttempts 包含首次调用的正数次数
     * @param maxDurationMillis 可空的正毫秒预算
     * @param warningOnRetry 可空，默认 false
     * @param intervalMillis 必填毫秒间隔
     * @throws IllegalArgumentException 配置不合法时抛出
     */
    @JsonCreator
    protected ConstantRetry(
        @JsonProperty("maxAttempts") Integer maxAttempts,
        @JsonProperty("maxDurationMillis") Long maxDurationMillis,
        @JsonProperty("warningOnRetry") Boolean warningOnRetry,
        @JsonProperty("intervalMillis") Long intervalMillis
    ) {
        super(maxAttempts, maxDurationMillis, warningOnRetry);
        if (intervalMillis == null || intervalMillis < 0) throw new IllegalArgumentException("intervalMillis must not be negative");
        this.intervalMillis = intervalMillis;
    }

    /**
     * 创建已校验策略，参数含义与构造协议一致。
     * @param maxAttempts 必填配置值
     * @param maxDurationMillis 可空的预算或默认值
     * @param warningOnRetry 可空的预算或默认值
     * @param intervalMillis 必填配置值
     * @return 新建不可变策略
     * @throws IllegalArgumentException 配置不合法时抛出
     */
    public static ConstantRetry create(Integer maxAttempts, Long maxDurationMillis, Boolean warningOnRetry, Long intervalMillis) {
        return new ConstantRetry(maxAttempts, maxDurationMillis, warningOnRetry, intervalMillis);
    }

    /**
     * 计算下一次等待。
     * @param attempts 已发生的正数调用次数
     * @return 非负毫秒间隔
     */
    @Override
    protected long delayMillis(int attempts) {
        return intervalMillis;
    }
}
