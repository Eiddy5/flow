package org.cses.flow.core.repositories.worker;

import org.cses.flow.executor.ExecutorEvent;
import org.cses.flow.worker.WorkerCommand;
import org.cses.flow.worker.WorkerTaskResult;
import org.cses.flow.worker.WorkerResultEvent;
import org.cses.flow.core.utils.TimeUtil;
import org.paas.json.JsonObject;
import org.paas.common.util.StringUtil;

/** 尚未完成消费的可靠消息事实；消费后删除，业务结果留在聚合。 */
public record ExecutionMessage(String companyId, String id, String executionId, String taskRunId,
        Integer attemptNumber, Long deliveryGeneration, Kind kind, String payload, long createdAt, Long publishedAt) {
    public enum Kind { WORKER, RESULT, ADVANCE }
    /**
     * 恢复完整已保存消息，不生成身份或时间。
     * @param companyId 非空白的原租户身份，不生成或更改
     * @param id 非空白的稳定消息身份，重发时沿用
     * @param executionId 非空白的原运行身份，不生成或更改
     * @param taskRunId 任务或结果的原节点身份，推进消息为 null
     * @param attemptNumber 任务或结果的正数原尝试编号，推进消息为 null
     * @param generation 结果许可正数代次，任务和推进消息为 null
     * @param kind 待消费消息的任务、结果或推进分类
     * @param payload 非空 JSON 正文，持久化后不覆盖
     * @param createdAt 原消息创建时间，UTC Unix 毫秒值
     * @param publishedAt Broker 确认时间，UTC Unix 毫秒值；null 表示尚未确认
     * @return 保留传入身份的完整快照，不生成新的业务身份
     */
    public static ExecutionMessage from(String companyId, String id, String executionId, String taskRunId,
            Integer attemptNumber, Long generation, Kind kind, String payload, long createdAt, Long publishedAt) {
        return new ExecutionMessage(companyId, id, executionId, taskRunId, attemptNumber, generation, kind, payload, createdAt, publishedAt);
    }
    /**
     * 创建待提交的任务命令及稳定新消息身份，发布重试沿用此身份。
     * @param companyId 非空白的原租户身份，不生成或更改
     * @param executionId 非空白的原运行身份，不生成或更改
     * @param taskRunId 非空白的原任务运行身份，不生成或更改
     * @param attemptNumber 从 1 开始的原业务尝试编号，重投保持不变
     * @return 可与聚合原子保存的任务命令消息
     */
    public static ExecutionMessage command(String companyId, String executionId, String taskRunId, int attemptNumber) {
        var command = WorkerCommand.from(StringUtil.newId(), companyId, executionId, taskRunId, attemptNumber);
        return from(companyId, command.id(), executionId, taskRunId, attemptNumber, null, Kind.WORKER,
                JsonObject.ToJsonContent(command), TimeUtil.now(), null);
    }
    /**
     * 保存确定结果正文及引用；技术接管不改变业务尝试。
     * @param companyId 非空白的原租户身份，不生成或更改
     * @param generation 从 1 开始的当前技术许可代次
     * @param result 非空的已完成原尝试结果，只读
     * @return 包含稳定新结果身份和原正文的待消费消息
     */
    public static ExecutionMessage result(String companyId, long generation, WorkerTaskResult result) {
        return from(companyId, StringUtil.newId(), result.executionId(), result.taskRunId(), result.attemptNumber(), generation,
                Kind.RESULT, JsonObject.ToJsonContent(result), TimeUtil.now(), null);
    }
    /**
     * 将有稳定身份的内部推进事件写入 outbox。
     * @param event 非空推进事件，沿用原消息身份
     * @return 包含稳定原身份的推进消息或原 JSON 恢复的事件
     */
    public static ExecutionMessage advance(ExecutorEvent event) {
        return from(event.companyId(), event.id(), event.executionId(), null, null, null, Kind.ADVANCE,
                JsonObject.ToJsonContent(event), TimeUtil.now(), null);
    }
    /**
     * 从原保存内容恢复任务命令。
     * @return 从原 JSON 正文恢复的独立命令快照
     */
    public WorkerCommand command() { return JsonObject.Parse(payload).asObject(WorkerCommand.class); }
    /**
     * 从原保存内容恢复业务结果。
     * @return 从原 JSON 正文恢复的独立结果快照
     */
    public WorkerTaskResult result() { return JsonObject.Parse(payload).asObject(WorkerTaskResult.class); }
    /**
     * 创建稳定结果引用，正文在 Executor 接纳后清理。
     * @return 引用原正文身份与技术代次的不可变结果事件
     */
    public WorkerResultEvent resultEvent() {
        return WorkerResultEvent.from(id, companyId, executionId, taskRunId, attemptNumber, deliveryGeneration);
    }
    /**
     * 从原保存内容恢复推进事件。
     * @return 包含稳定原身份的推进消息或原 JSON 恢复的事件
     */
    public ExecutorEvent advance() { return JsonObject.Parse(payload).asObject(ExecutorEvent.class); }
}
