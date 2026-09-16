package org.cses.flow.infrastructure.repositories.triggers.entries;

import org.cses.flow.scheduler.events.SchedulerEvent;
import org.flow.gen.flow.pojos.SchedulerEventsObject;
import org.cses.flow.infrastructure.repositories.triggers.codec.SchedulerEventJsonCodec;

/** 内部事件与持久化 outbox 行的转换。 */
public class SchedulerEventEntry extends SchedulerEventsObject {
    /**
     * 保存一条待发布事件。
     * @param event 完整且已校验的事件
     * @param now 服务端毫秒时间
     * @return 初始待投递行
     */
    public static SchedulerEventEntry from(SchedulerEvent event, long now) {
        SchedulerEventEntry entry = new SchedulerEventEntry();
        entry.companyId = event.companyId();
        entry.id = event.id();
        entry.kind = event.kind();
        entry.payload = SchedulerEventJsonCodec.encode(event);
        entry.createdAt = now;
        entry.retryAt = now;
        entry.attempts = 0;
        return entry;
    }
    /** @return 原始事件协议 */
    public SchedulerEvent to() { return SchedulerEventJsonCodec.decode(payload); }
}
