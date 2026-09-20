package org.cses.flow.core.domains.tasks.retries;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** ExponentialRetry 的不可变策略定义。 */
@lombok.EqualsAndHashCode(callSuper = true)
public class ExponentialRetry extends AbstractRetry {
    private long intervalMillis;
    private long maxIntervalMillis;
    private double delayFactor;

    /**
     * 校验并创建策略；毫秒间隔必须符合策略范围。
     * @param maxAttempts 包含首次调用的正数次数
     * @param maxDurationMillis 可空的正毫秒预算
     * @param warningOnRetry 可空，默认 false
     * @param intervalMillis 必填毫秒间隔
     * @param maxIntervalMillis 必填毫秒间隔
     * @param delayFactor 有限且不小于 1，null 采用 2
     * @throws IllegalArgumentException 配置不合法时抛出
     */
    @JsonCreator
    protected ExponentialRetry(
        @JsonProperty("maxAttempts") Integer maxAttempts,
        @JsonProperty("maxDurationMillis") Long maxDurationMillis,
        @JsonProperty("warningOnRetry") Boolean warningOnRetry,
        @JsonProperty("intervalMillis") Long intervalMillis,
        @JsonProperty("maxIntervalMillis") Long maxIntervalMillis,
        @JsonProperty("delayFactor") Double delayFactor
    ) {
        super(maxAttempts, maxDurationMillis, warningOnRetry);
        if (intervalMillis == null || intervalMillis < 1 || maxIntervalMillis == null || maxIntervalMillis < intervalMillis || delayFactor != null && (!Double.isFinite(delayFactor) || delayFactor < 1)) throw new IllegalArgumentException("Invalid exponential retry intervals or factor");
        if (delayFactor == null) delayFactor = 2d;
        this.intervalMillis = intervalMillis;
        this.maxIntervalMillis = maxIntervalMillis;
        this.delayFactor = delayFactor;
    }

    /**
     * 创建已校验策略，参数含义与构造协议一致。
     * @param maxAttempts 必填配置值
     * @param maxDurationMillis 可空的预算或默认值
     * @param warningOnRetry 可空的预算或默认值
     * @param intervalMillis 必填配置值
     * @param maxIntervalMillis 必填配置值
     * @param delayFactor 可空的预算或默认值
     * @return 新建不可变策略
     * @throws IllegalArgumentException 配置不合法时抛出
     */
    public static ExponentialRetry create(Integer maxAttempts, Long maxDurationMillis, Boolean warningOnRetry, Long intervalMillis, Long maxIntervalMillis, Double delayFactor) {
        return new ExponentialRetry(maxAttempts, maxDurationMillis, warningOnRetry, intervalMillis, maxIntervalMillis, delayFactor);
    }

    /**
     * 计算下一次等待。
     * @param attempts 已发生的正数调用次数
     * @return 非负毫秒间隔
     */
    @Override
    protected long delayMillis(int attempts) {
        double delay = intervalMillis * Math.pow(delayFactor, attempts - 1);
        return delay >= maxIntervalMillis ? maxIntervalMillis : Math.round(delay);
    }
}
