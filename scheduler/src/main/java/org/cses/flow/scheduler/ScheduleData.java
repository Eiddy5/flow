package org.cses.flow.scheduler;

import org.cses.flow.core.domains.triggers.ScheduledTrigger;
import org.paas.common.util.StringUtil;
import org.cses.flow.core.utils.RequiredUtil;

/** 数据库中当前等待命中的坐标；失败重试不会更换这次命中的身份。 */
public record ScheduleData(String companyId, String triggerId, String flowKey, String triggerKey,
    String schedule, long scheduledAt, String eventId, String executionId) {
    /** 校验持久化坐标，损坏的数据不能被当成一次新的有效命中。 */
    public ScheduleData {
        RequiredUtil.required(companyId, "Schedule companyId");
        RequiredUtil.required(triggerId, "Schedule triggerId");
        RequiredUtil.required(flowKey, "Schedule flowKey");
        RequiredUtil.required(triggerKey, "Schedule triggerKey");
        RequiredUtil.required(schedule, "Schedule rule");
        RequiredUtil.required(eventId, "Schedule eventId");
        RequiredUtil.required(executionId, "Schedule executionId");
        if (scheduledAt < 0) throw new IllegalArgumentException("Schedule time must not be negative");
    }

    /**
     * 从持久化列恢复同一次计划，不分配新的命中身份。
     * @param companyId 非空租户
     * @param triggerId 非空触发器身份
     * @param flowKey 非空流程键
     * @param triggerKey 非空触发器键
     * @param schedule 非空规范化计划规则
     * @param scheduledAt 非负 UTC 毫秒计划时间
     * @param eventId 非空命中事件身份
     * @param executionId 非空预分配运行身份
     * @return 校验后的不可变计划坐标
     */
    public static ScheduleData from(String companyId, String triggerId, String flowKey, String triggerKey,
            String schedule, long scheduledAt, String eventId, String executionId) {
        return new ScheduleData(companyId, triggerId, flowKey, triggerKey, schedule, scheduledAt, eventId, executionId);
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
        return from(companyId, trigger.id(), flowKey, trigger.key(), trigger.scheduleKey(),
            scheduledAt, StringUtil.newId(), StringUtil.newId());
    }
}
