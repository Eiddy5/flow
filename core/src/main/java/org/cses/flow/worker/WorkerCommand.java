package org.cses.flow.worker;

import org.cses.flow.queues.annotations.FlowQueue;
import org.cses.flow.queues.event.Event;
import org.cses.flow.common.Fields;
import java.util.Objects;

/** 精确任务命令，只传持久化身份，不传插件或可恢复会话。 */
@FlowQueue(name = WorkerCommand.QUEUE_NAME, topic = WorkerCommand.QUEUE_NAME)
public record WorkerCommand(String id, String companyId, String executionId, String taskRunId, int attemptNumber) implements Event {
    public static final String QUEUE_NAME = "flow-worker-command";
    /**
     * 校验不可变身份与正数尝试编号。
     * @throws NullPointerException 任一身份为 null 时抛出
     * @throws IllegalArgumentException 任一身份为空白或尝试编号小于 1 时抛出
     */
    public WorkerCommand {
        Objects.requireNonNull(id, Fields.ID);
        Objects.requireNonNull(companyId, Fields.COMPANY_ID);
        Objects.requireNonNull(executionId, Fields.EXECUTION_ID);
        Objects.requireNonNull(taskRunId, Fields.TASK_RUN_ID);
        if (id.isBlank() || companyId.isBlank() || executionId.isBlank() || taskRunId.isBlank() || attemptNumber < 1)
            throw new IllegalArgumentException("Invalid Worker command identity");
    }
    /** 沿用原命令和原业务尝试身份创建传输消息。
     * @param id 非空白的稳定消息身份，重发时沿用
     * @param companyId 非空白的原租户身份，不生成或更改
     * @param executionId 非空白的原运行身份，不生成或更改
     * @param taskRunId 非空白的原任务运行身份，不生成或更改
     * @param attemptNumber 从 1 开始的原业务尝试编号，重投保持不变
     * @return 保留传入身份的完整快照，不生成新的业务身份
     * @throws NullPointerException 任一身份为 null 时抛出
     * @throws IllegalArgumentException 任一身份为空白或尝试编号小于 1 时抛出
     */
    public static WorkerCommand from(String id, String companyId, String executionId, String taskRunId, int attemptNumber) {
        return new WorkerCommand(id, companyId, executionId, taskRunId, attemptNumber);
    }
    /**
     * 返回原运行作为队列路由键。
     * @return 原 Execution 身份，用于传输路由
     */
    @Override
    public String key() { return executionId; }
}
