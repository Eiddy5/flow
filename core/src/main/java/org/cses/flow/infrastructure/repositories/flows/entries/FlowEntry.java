package org.cses.flow.infrastructure.repositories.flows.entries;

import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.infrastructure.repositories.flows.codec.TriggerJsonCodec;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.infrastructure.repositories.flows.codec.ActorRefJsonCodec;
import org.cses.flow.infrastructure.repositories.flows.codec.AuditStatusCodec;
import org.cses.flow.infrastructure.repositories.flows.codec.DataJsonCodec;
import org.cses.flow.infrastructure.repositories.flows.codec.FlowVariablesJsonCodec;
import org.flow.gen.flow.pojos.FlowsObject;

import java.util.List;

/**
 * Persistence mapping for both editable and deployed Flow states.
 */
public class FlowEntry extends FlowsObject {

    /**
     * 将 Flow 定义、触发配置和审计事实编码为一行持久化数据。
     * @param flow 非空 Flow，只读不修改
     * @param rowId 新行的非空技术身份
     * @param version Repository 分配的正版本号
     * @return 编码后的新行，不共享可变容器
     * @throws IllegalArgumentException 字段无法编码时抛出
     * @throws NullPointerException Flow 为空时抛出
     */
    public static FlowEntry from(
            Flow flow,
            String rowId,
            long version
    ) {
        FlowEntry entry = new FlowEntry();
        entry.id = rowId;
        entry.key = flow.key();
        entry.companyId = flow.companyId();
        entry.version = version;
        entry.draft = flow.draft();
        entry.source = flow.source();
        entry.description = flow.description();
        entry.status = AuditStatusCodec.encode(flow.status());
        entry.creator = ActorRefJsonCodec.encode(flow.creator());
        entry.updater = ActorRefJsonCodec.encode(flow.updater());
        entry.deleter = flow.deleter()
            .map(ActorRefJsonCodec::encode)
            .orElse(null);
        entry.createdAt = flow.createdAt();
        entry.updatedAt = flow.updatedAt();
        entry.deletedAt = flow.deletedAt()
            .orElse(null);
        entry.inputs = DataJsonCodec.encodeJsonb(flow.inputs());
        entry.outputs = DataJsonCodec.encodeJsonb(flow.outputs());
        entry.triggers = TriggerJsonCodec.encode(flow.triggers());
        entry.variables = FlowVariablesJsonCodec.encodeJsonb(flow.variables());
        return entry;
    }

    /**
     * Restores a Flow without Task rows.
     *
     * @return a detached Flow containing this entry's persisted state
     */
    public Flow to() {
        return to(List.of());
    }

    /**
     * 从行数据和指定任务树恢复完整 Flow，保留触发器原身份。
     * @param tasks 该版本任务快照，null 作为空集合处理
     * @return 容器独立但任务元素共享的完整 Flow
     * @throws IllegalArgumentException 持久化字段或触发配置非法时抛出
     * @throws IllegalStateException 必需审计时间缺失时抛出
     * @throws NullPointerException 任务集合包含空元素时抛出
     */
    public Flow to(List<Task> tasks) {
        return Flow.rehydrate(
            id,
            companyId,
            key,
            Boolean.TRUE.equals(draft),
            version,
            description,
            FlowVariablesJsonCodec.decode(variables),
            DataJsonCodec.decodeInputs(inputs, "Flow.inputs"),
            DataJsonCodec.decodeOutputs(outputs, "Flow.outputs"),
            tasks,
            TriggerJsonCodec.decode(triggers),
            AuditStatusCodec.decode(status, "Flow.status"),
            ActorRefJsonCodec.decode(creator, "Flow.creator"),
            ActorRefJsonCodec.decode(updater, "Flow.updater"),
            ActorRefJsonCodec.decodeOptional(deleter, "Flow.deleter"),
            requiredEpochMillis(createdAt, "Flow.createdAt"),
            requiredEpochMillis(updatedAt, "Flow.updatedAt"),
            deletedAt == null
                ? null
                : deletedAt,
            source
        );
    }

    static long requiredEpochMillis(Long value, String field) {
        if (value == null) {
            throw new IllegalStateException(
                "Persisted " + field + " must not be null"
            );
        }
        return value;
    }
}
