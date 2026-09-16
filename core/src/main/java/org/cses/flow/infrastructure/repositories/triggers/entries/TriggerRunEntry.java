package org.cses.flow.infrastructure.repositories.triggers.entries;

import org.cses.flow.scheduler.events.SchedulerEvent;
import org.flow.gen.flow.pojos.TriggerRunsObject;
import org.cses.flow.infrastructure.repositories.triggers.codec.SchedulerEventJsonCodec;

/** 每次触发的稳定身份与参数记录。 */
public class TriggerRunEntry extends TriggerRunsObject {
    /**
     * 从命中事件创建触发记录。
     * @param event 已校验的命中事件
     * @return 尚未被 Executor 队列受理的记录
     */
    public static TriggerRunEntry from(SchedulerEvent event) {
        TriggerRunEntry entry = new TriggerRunEntry();
        entry.companyId = event.companyId(); entry.id = event.id();
        entry.flowKey = event.flowKey(); entry.flowVersion = event.flowVersion();
        entry.triggerKey = event.triggerKey(); entry.scheduledAt = event.scheduledAt();
        entry.executionId = event.executionId(); entry.actorId = event.actorId();
        entry.inputs = SchedulerEventJsonCodec.encodeInputs(event.inputs());
        return entry;
    }
    /** @return 与持久化事实一致的命中事件 */
    public SchedulerEvent to() {
        return SchedulerEvent.from("TRIGGER_FIRED", companyId, id, flowKey, flowVersion,
            triggerKey, scheduledAt, executionId, actorId, SchedulerEventJsonCodec.decodeInputs(inputs));
    }
}
