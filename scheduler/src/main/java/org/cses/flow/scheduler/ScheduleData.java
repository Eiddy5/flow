package org.cses.flow.scheduler;

import org.cses.flow.core.domains.triggers.ScheduledTrigger;
import org.paas.common.util.StringUtil;
import org.cses.flow.core.utils.RequiredUtil;

/** 数据库中当前等待命中的坐标；失败重试不会更换这次命中的身份。 */
public record ScheduleData(String companyId, String flowKey, String triggerKey,
    String schedule, long scheduledAt, String eventId, String executionId) {
    /** 校验持久化坐标，损坏的数据不能被当成一次新的有效命中。 */
    public ScheduleData {
        RequiredUtil.required(companyId, "Schedule companyId");
        RequiredUtil.required(flowKey, "Schedule flowKey");
        RequiredUtil.required(triggerKey, "Schedule triggerKey");
        RequiredUtil.required(schedule, "Schedule rule");
        RequiredUtil.required(eventId, "Schedule eventId");
        RequiredUtil.required(executionId, "Schedule executionId");
        if (scheduledAt < 0) throw new IllegalArgumentException("Schedule time must not be negative");
    }

    /**
     * 为下一次命中分配稳定身份。
     *
     * @param companyId 租户
     *
     * @param flowKey Flow 名称
     *
     * @param trigger 定时定义
     *
     * @param scheduledAt 计划毫秒时间
     *
     * @return 可持久化的调度数据
     */
    public static ScheduleData next(String companyId, String flowKey, ScheduledTrigger trigger, long scheduledAt) {
        return new ScheduleData(companyId, flowKey, trigger.key(), trigger.scheduleKey(),
            scheduledAt, StringUtil.newId(), StringUtil.newId());
    }
}
