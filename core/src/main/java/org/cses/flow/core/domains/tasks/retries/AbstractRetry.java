package org.cses.flow.core.domains.tasks.retries;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.OptionalLong;

/** 可扩展的重试定义；只计算下一次时间，不执行任务或修改运行事实。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ConstantRetry.class, name = "constant"),
    @JsonSubTypes.Type(value = ExponentialRetry.class, name = "exponential"),
    @JsonSubTypes.Type(value = RandomRetry.class, name = "random")
})
@lombok.EqualsAndHashCode
public abstract class AbstractRetry {
    protected int maxAttempts;
    protected Long maxDurationMillis;
    protected boolean warningOnRetry;

    /**
     * 创建有限重试定义，次数包含首次调用。
     * @param maxAttempts 正数，不能为 null
     * @param maxDurationMillis 可空的正毫秒预算
     * @param warningOnRetry 重试成功是否标记警告，null 采用 false
     * @throws IllegalArgumentException 配置越界时抛出
     */
    protected AbstractRetry(Integer maxAttempts, Long maxDurationMillis, Boolean warningOnRetry) {
        if (maxAttempts == null || maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        if (maxDurationMillis != null && maxDurationMillis < 1) throw new IllegalArgumentException("maxDurationMillis must be positive");
        this.maxAttempts = maxAttempts;
        this.maxDurationMillis = maxDurationMillis;
        this.warningOnRetry = Boolean.TRUE.equals(warningOnRetry);
    }

    /**
     * 计算下一次调用时间；达到次数或时间预算时停止，不中断正在执行的调用。
     * @param attempts 已发生的正数调用次数
     * @param startedAt 首次调用的非负毫秒时间
     * @param failedAt 最近失败的毫秒时间，不早于首次调用
     * @return 下一次毫秒时间，空表示预算耗尽
     * @throws IllegalArgumentException 运行事实或策略返回间隔不合法时抛出
     */
    public OptionalLong nextRetryAt(int attempts, long startedAt, long failedAt) {
        if (attempts < 1 || startedAt < 0 || failedAt < startedAt) throw new IllegalArgumentException("Invalid retry history");
        if (attempts >= maxAttempts) return OptionalLong.empty();
        long delay = delayMillis(attempts);
        if (delay < 0) throw new IllegalArgumentException("Retry delay must not be negative");
        if (delay > Long.MAX_VALUE - failedAt) return OptionalLong.empty();
        long next = failedAt + delay;
        if (maxDurationMillis != null && next - startedAt > maxDurationMillis) return OptionalLong.empty();
        return OptionalLong.of(next);
    }

    /**
     * 计算失败后的等待间隔。
     * @param attempts 已发生的正数调用次数
     * @return 非负毫秒间隔
     */
    protected abstract long delayMillis(int attempts);

    /**
     * 检查真正开始下一次调用时是否仍在预算内。
     * @param startedAt 首次开始毫秒时间
     * @param now 当前毫秒时间
     * @return 未超过预算时为 true
     */
    public boolean permitsStart(long startedAt, long now) {
        return now >= startedAt && (maxDurationMillis == null || now - startedAt <= maxDurationMillis);
    }

    /** @return 重试后成功是否保留警告 */
    public boolean warningOnRetry() { return warningOnRetry; }
}
