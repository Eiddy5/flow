package org.cses.flow.core.repositories.worker;

/** 当前执行许可与有限接纳身份，独立于待消费消息记录。 */
public record WorkerDelivery(String companyId, String executionId, String taskRunId, int attemptNumber,
        long deliveryGeneration, String ownerId, Long leaseUntilAt, Phase phase, String resultId) {
    public enum Phase { RUNNING, REPORTED, SETTLED }
    /**
     * 按持久化或受控领取事实创建不可变许可。
     * @param companyId 非空白的原租户身份，不生成或更改
     * @param executionId 非空白的原运行身份，不生成或更改
     * @param taskRunId 非空白的原任务运行身份，不生成或更改
     * @param attemptNumber 从 1 开始的原业务尝试编号，重投保持不变
     * @param generation 从 1 开始的当前技术许可代次
     * @param ownerId 非空白的本次 Worker 所有者身份
     * @param leaseUntilAt 许可到期 UTC Unix 毫秒值，恢复历史事实时允许 null
     * @param phase 当前许可的运行、结果已报告或结果已接纳阶段
     * @param resultId 稳定结果身份，尚无结果时允许 null
     * @return 保留传入身份的完整快照，不生成新的业务身份
     */
    public static WorkerDelivery from(String companyId, String executionId, String taskRunId, int attemptNumber,
            long generation, String ownerId, Long leaseUntilAt, Phase phase, String resultId) {
        return new WorkerDelivery(companyId, executionId, taskRunId, attemptNumber, generation, ownerId, leaseUntilAt, phase, resultId);
    }
    /**
     * 保留原许可身份，记录确定结果或已接纳事实。
     * @param target 报告结果或结果已接纳的目标技术阶段
     * @param id 非空白的稳定消息身份，重发时沿用
     * @return 当前许可的结果快照，或保留原许可身份的更新快照
     */
    public WorkerDelivery reported(Phase target, String id) {
        return from(companyId, executionId, taskRunId, attemptNumber, deliveryGeneration, ownerId, leaseUntilAt, target, id);
    }
}
