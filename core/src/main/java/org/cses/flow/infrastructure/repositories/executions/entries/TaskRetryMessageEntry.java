package org.cses.flow.infrastructure.repositories.executions.entries;

import org.cses.flow.core.repositories.executions.ExecutionRepository.PendingRetry;
import org.flow.gen.flow.pojos.TaskRetryMessagesObject;

/** 重试 outbox 的完整表行表示。 */
public class TaskRetryMessageEntry extends TaskRetryMessagesObject {
    /**
     * 将不可变重试坐标转换为尚未发布的 outbox 行，不生成身份或时间。
     * @param retry 完整的租户、运行、任务和重试坐标
     * @return 新的数据库行表示
     */
    public static TaskRetryMessageEntry from(PendingRetry retry) {
        TaskRetryMessageEntry entry = new TaskRetryMessageEntry();
        entry.companyId = retry.companyId();
        entry.executionId = retry.executionId();
        entry.taskRunId = retry.taskRunId();
        entry.attemptNumber = retry.attemptNumber();
        entry.deliverAt = retry.deliverAt();
        return entry;
    }

    /** 恢复已持久化的不可变重试投递坐标，不生成身份或时间。 */
    public PendingRetry to() {
        return PendingRetry.from(companyId, executionId, taskRunId, attemptNumber, deliverAt);
    }
}
