package org.cses.flow.core.domains.tasks.retries;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** RandomRetry 的不可变策略定义。 */
@lombok.EqualsAndHashCode(callSuper = true)
public class RandomRetry extends AbstractRetry {
    private long minIntervalMillis;
    private long maxIntervalMillis;

    /**
     * 校验并创建策略；毫秒间隔必须符合策略范围。
     * @param maxAttempts 包含首次调用的正数次数
     * @param maxDurationMillis 可空的正毫秒预算
     * @param warningOnRetry 可空，默认 false
     * @param minIntervalMillis 必填毫秒间隔
     * @param maxIntervalMillis 必填毫秒间隔
     * @throws IllegalArgumentException 配置不合法时抛出
     */
    @JsonCreator
    protected RandomRetry(
        @JsonProperty("maxAttempts") Integer maxAttempts,
        @JsonProperty("maxDurationMillis") Long maxDurationMillis,
        @JsonProperty("warningOnRetry") Boolean warningOnRetry,
        @JsonProperty("minIntervalMillis") Long minIntervalMillis,
        @JsonProperty("maxIntervalMillis") Long maxIntervalMillis
    ) {
        super(maxAttempts, maxDurationMillis, warningOnRetry);
        if (minIntervalMillis == null || minIntervalMillis < 0 || maxIntervalMillis == null || maxIntervalMillis <= minIntervalMillis) throw new IllegalArgumentException("Invalid random retry interval range");
        this.minIntervalMillis = minIntervalMillis;
        this.maxIntervalMillis = maxIntervalMillis;
    }

    /**
     * 创建已校验策略，参数含义与构造协议一致。
     * @param maxAttempts 必填配置值
     * @param maxDurationMillis 可空的预算或默认值
     * @param warningOnRetry 可空的预算或默认值
     * @param minIntervalMillis 必填配置值
     * @param maxIntervalMillis 必填配置值
     * @return 新建不可变策略
     * @throws IllegalArgumentException 配置不合法时抛出
     */
    public static RandomRetry create(Integer maxAttempts, Long maxDurationMillis, Boolean warningOnRetry, Long minIntervalMillis, Long maxIntervalMillis) {
        return new RandomRetry(maxAttempts, maxDurationMillis, warningOnRetry, minIntervalMillis, maxIntervalMillis);
    }

    /**
     * 计算下一次等待。
     * @param attempts 已发生的正数调用次数
     * @return 非负毫秒间隔
     */
    @Override
    protected long delayMillis(int attempts) {
        return java.util.concurrent.ThreadLocalRandom.current().nextLong(minIntervalMillis, maxIntervalMillis);
    }
}
