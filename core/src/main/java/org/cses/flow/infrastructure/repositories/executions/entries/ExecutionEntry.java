package org.cses.flow.infrastructure.repositories.executions.entries;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.Origin;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.infrastructure.repositories.executions.codec.ExecutionInputsJsonCodec;
import org.cses.flow.infrastructure.repositories.executions.codec.GenerationJsonCodec;
import org.cses.flow.infrastructure.repositories.executions.codec.StateJsonCodec;
import org.cses.flow.infrastructure.repositories.executions.codec.TaskRunSnapshotsJsonCodec;
import org.cses.flow.infrastructure.repositories.flows.codec.ActorRefJsonCodec;
import org.flow.gen.flow.pojos.ExecutionsObject;

import java.util.List;

public class ExecutionEntry extends ExecutionsObject {

    /**
     * 将完整 Execution、继承快照及随身版本转换为数据库字段，不生成领域事实。
     * @param execution 已校验的领域快照，新建对象的 lock 为 null
     * @return 新的完整数据库 Entry
     */
    public static ExecutionEntry from(Execution execution) {
        ExecutionEntry entry = new ExecutionEntry();
        entry.id = execution.id();
        entry.lock = execution.lock();
        entry.companyId = execution.companyId();
        entry.flowKey = execution.flowKey();
        entry.flowVersion = execution.flowVersion();
        entry.parentId = execution.origin().parentId();
        entry.parentTaskRunId = execution.parentTaskRunId();
        entry.originId = execution.origin().originId();
        entry.inheritedTaskRuns = TaskRunSnapshotsJsonCodec.encode(execution.inheritedTaskRuns());
        entry.inputs = ExecutionInputsJsonCodec.encode(execution.inputs());
        entry.state = StateJsonCodec.encode(execution.state());
        entry.generation = GenerationJsonCodec.encode(execution.generation());
        entry.creator = ActorRefJsonCodec.encode(execution.creator());
        entry.createdAt = execution.createdAt();
        return entry;
    }

    /**
     * 合并持久化继承快照与自有 TaskRun，恢复包含加载版本的完整 Execution。
     * @param taskRuns 独立解码并保持顺序的自有 TaskRun
     * @return 校验后的领域快照，保留数据库版本及来源关系
     * @throws IllegalArgumentException 来源、历史或版本非法
     * @throws IllegalStateException Flow 版本或 lock 缺失
     */
    public Execution to(List<TaskRun> taskRuns) {
        if (lock == null) throw new IllegalStateException("Persisted Execution lock must not be null");
        if (flowVersion == null) {
            throw new IllegalStateException(
                "Persisted Execution flowVersion must not be null"
            );
        }
        return Execution.rehydrate(
            id,
            companyId,
            ActorRefJsonCodec.decode(creator, "Execution.creator"),
            requiredEpochMillis(createdAt, "Execution.createdAt"),
            flowKey,
            flowVersion,
            ExecutionInputsJsonCodec.decode(inputs),
            GenerationJsonCodec.decode(generation),
            StateJsonCodec.decode(state),
            taskRuns,
            Origin.create(parentId, originId),
            TaskRunSnapshotsJsonCodec.decode(inheritedTaskRuns),
            parentTaskRunId,
            lock
        );
    }

    private static long requiredEpochMillis(
        Long value,
        String field
    ) {
        if (value == null) {
            throw new IllegalStateException(
                "Persisted " + field + " must not be null"
            );
        }
        return value;
    }
}
