package org.cses.flow.core.domains.triggers;

/** 能计算下一次 UTC 毫秒计划时间的触发定义。 */
public interface ScheduledTrigger extends Trigger {
    /**
     * @param after 排他的 UTC 毫秒下界
     * @return 下一次时间；计划耗尽时为 null
     */
    Long nextAt(long after);
    /** @return 仅包含时间规则的稳定比较值，用于识别计划变更 */
    String scheduleKey();
}
