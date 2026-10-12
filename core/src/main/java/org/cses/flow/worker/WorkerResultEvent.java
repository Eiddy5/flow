package org.cses.flow.worker;

import org.cses.flow.queues.annotations.FlowQueue;
import org.cses.flow.queues.event.Event;
import org.cses.flow.common.Fields;
import java.util.Objects;

/** 队列结果引用，正文保存在消费前的 outbox，避免大输出进入消息。 */
@FlowQueue(name = WorkerResultEvent.QUEUE_NAME, topic = WorkerResultEvent.QUEUE_NAME)
public record WorkerResultEvent(String id, String companyId, String executionId, String taskRunId, int attemptNumber, long deliveryGeneration) implements Event {
    public static final String QUEUE_NAME = "flow-worker-result";
    /**
     * 校验结果身份与投递许可代次。
     * @throws NullPointerException 任一身份为 null 时抛出
     * @throws IllegalArgumentException 任一身份为空白、尝试编号或代次小于 1 时抛出
     */
    public WorkerResultEvent {
        Objects.requireNonNull(id, Fields.ID);
        Objects.requireNonNull(companyId, Fields.COMPANY_ID);
        Objects.requireNonNull(executionId, Fields.EXECUTION_ID);
        Objects.requireNonNull(taskRunId, Fields.TASK_RUN_ID);
        if (id.isBlank() || companyId.isBlank() || executionId.isBlank() || taskRunId.isBlank() || attemptNumber < 1 || deliveryGeneration < 1)
            throw new IllegalArgumentException("Invalid Worker result identity");
    }
    /** 沿用稳定结果身份创建传输引用。
     * @param id 非空白的稳定消息身份，重发时沿用
     * @param companyId 非空白的原租户身份，不生成或更改
     * @param executionId 非空白的原运行身份，不生成或更改
     * @param taskRunId 非空白的原任务运行身份，不生成或更改
     * @param attemptNumber 从 1 开始的原业务尝试编号，重投保持不变
     * @param deliveryGeneration 从 1 开始的当前技术许可代次
     * @return 保留传入身份的完整快照，不生成新的业务身份
     * @throws NullPointerException 任一身份为 null 时抛出
     * @throws IllegalArgumentException 任一身份为空白、尝试编号或代次小于 1 时抛出
     */
    public static WorkerResultEvent from(String id, String companyId, String executionId, String taskRunId, int attemptNumber, long deliveryGeneration) {
        return new WorkerResultEvent(id, companyId, executionId, taskRunId, attemptNumber, deliveryGeneration);
    }
    /**
     * 返回所属运行作为队列路由键。
     * @return 原 Execution 身份，用于传输路由
     */
    @Override
    public String key() { return executionId; }
}
