package org.cses.flow.scheduler.events;

import org.cses.flow.core.utils.RequiredUtil;
import org.cses.flow.queues.annotations.FlowQueue;
import org.cses.flow.queues.event.Event;
import java.util.Map;

/** 调度配置变更或一次已持久化命中的事件。只在可信内部队列传输。 */
@FlowQueue(name = "flow-scheduler-event", topic = "flow-scheduler-event")
public record SchedulerEvent(String kind, String companyId, String id, String flowKey,
    long flowVersion, String triggerKey, Long scheduledAt, String executionId,
    String actorId, Map<String, Object> inputs, String sourceKey) implements Event {
    public static String QUEUE_NAME = "flow-scheduler-event";
    /** 校验事件形状并复制参数容器。 */
    public SchedulerEvent {
        RequiredUtil.required(companyId, "Event companyId");
        RequiredUtil.required(id, "Event id");
        RequiredUtil.required(flowKey, "Event flowKey");
        RequiredUtil.required(actorId, "Event actorId");
        if (flowVersion < 1) throw new IllegalArgumentException("Event flowVersion must be positive");
        if (!"FLOW_CHANGED".equals(kind) && !"TRIGGER_FIRED".equals(kind) && !"FLOW_TERMINATED".equals(kind))
            throw new IllegalArgumentException("Unknown scheduler event kind: " + kind);
        if ("TRIGGER_FIRED".equals(kind)) {
            RequiredUtil.required(triggerKey, "Event triggerKey");
            RequiredUtil.required(executionId, "Event executionId");
            RequiredUtil.required(sourceKey, "Event sourceKey");
            if (sourceKey.length() > 256) throw new IllegalArgumentException("Event sourceKey too long");
            if (scheduledAt != null && scheduledAt < 0) throw new IllegalArgumentException("Event scheduledAt");
        }
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
    }
    /**
     * 创建事件协议。
     * @param kind 事件种类
     * @param companyId 租户
     * @param id 稳定事件身份
     * @param flowKey Flow名称
     * @param flowVersion 精确版本
     * @param triggerKey 命中时必填
     * @param scheduledAt 命中计划毫秒时间
     * @param executionId 命中时预分配的实例身份
     * @param actorId 可信操作者
     * @param inputs 固定输入
     *
     * @return 校验后的事件
     */
    public static SchedulerEvent from(String kind, String companyId, String id, String flowKey,
        long flowVersion, String triggerKey, Long scheduledAt, String executionId,
        String actorId, Map<String, Object> inputs) {
        return new SchedulerEvent(kind, companyId, id, flowKey, flowVersion, triggerKey,
            scheduledAt, executionId, actorId, inputs, "TRIGGER_FIRED".equals(kind) ? "schedule:" + scheduledAt : null);
    }
    /**
     * 创建具有明确来源身份的触发命中事件。
     * @param companyId 租户
     * @param id 事件身份
     * @param flowKey 目标流程
     * @param flowVersion 固定版本
     * @param triggerKey 触发器配置名称
     * @param scheduledAt 可空计划 UTC 毫秒时间
     * @param executionId 稳定实例身份
     * @param actorId 可信操作者
     * @param inputs 已绑定的输入快照
     * @param sourceKey 来源去重键，不超过 256 字符
     * @return 不可变命中事件
     */
    public static SchedulerEvent occurrence(String companyId, String id, String flowKey, long flowVersion,
        String triggerKey, Long scheduledAt, String executionId, String actorId, Map<String, Object> inputs,
        String sourceKey) {
        return new SchedulerEvent("TRIGGER_FIRED", companyId, id, flowKey, flowVersion,
            triggerKey, scheduledAt, executionId, actorId, inputs, sourceKey);
    }
    /**
     * @return 稳定的内部事件路由键 */
    @Override
    public String key() { return id; }
}
