package org.cses.flow.executor;

import org.cses.flow.queues.annotations.FlowQueue;
import org.cses.flow.queues.event.Event;

/** 专用延迟消息，定位某个任务的下一次调用，不携带业务快照。 */
@FlowQueue(name = "flow-delay", topic = "flow-delay")
public record TaskRetryEvent(String companyId, String executionId, String taskRunId,
                             int attemptNumber, long deliverAt) implements Event {
    public static String QUEUE_NAME = "flow-delay";

    /** 校验反序列化和创建入口的身份、编号与计划时间。 */
    public TaskRetryEvent {
        if (companyId == null || companyId.isBlank() || executionId == null || executionId.isBlank()
                || taskRunId == null || taskRunId.isBlank() || attemptNumber < 2 || deliverAt < 0) {
            throw new IllegalArgumentException("Invalid task retry event");
        }
    }

    /**
     * 从已持久化计划构造消息。
     * @param companyId 租户身份
     * @param executionId 运行身份
     * @param taskRunId 任务运行身份
     * @param attemptNumber 预期下一次调用编号
     * @param deliverAt 最早允许调用的毫秒时间
     * @return 已校验消息
     */
    public static TaskRetryEvent from(String companyId, String executionId, String taskRunId,
                                     int attemptNumber, long deliverAt) {
        return new TaskRetryEvent(companyId, executionId, taskRunId, attemptNumber, deliverAt);
    }

    /** @return 同一运行的路由身份 */
    @Override
    public String key() { return executionId; }
}
