package org.cses.flow.scheduler.repositories.entries;

import org.cses.flow.scheduler.ScheduleData;
import org.flow.gen.flow.pojos.TriggerSchedulesObject;

/** 调度计划的关系列映射；运行失败计数与命中身份分别保存。 */
public class ScheduleEntry extends TriggerSchedulesObject {
    /**
     * 创建尚未尝试的新计划行。
     * @param data 非空计划坐标，读取但不修改
     * @return 到期可领取且失败次数为零的完整行
     */
    public static ScheduleEntry from(ScheduleData data) {
        var entry = new ScheduleEntry();
        entry.companyId = data.companyId();
        entry.id = data.triggerId();
        entry.flowKey = data.flowKey();
        entry.triggerKey = data.triggerKey();
        entry.schedule = data.schedule();
        entry.scheduledAt = data.scheduledAt();
        entry.eventId = data.eventId();
        entry.executionId = data.executionId();
        entry.availableAt = data.scheduledAt();
        entry.attempts = 0;
        return entry;
    }

    /** @return 保留原始事件和运行身份的计划坐标；损坏字段由坐标构造校验拒绝 */
    public ScheduleData to() {
        return ScheduleData.from(companyId, id, flowKey, triggerKey, schedule, scheduledAt, eventId, executionId);
    }
}
