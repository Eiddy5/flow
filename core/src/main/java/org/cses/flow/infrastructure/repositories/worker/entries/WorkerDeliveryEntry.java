package org.cses.flow.infrastructure.repositories.worker.entries;

import org.cses.flow.core.repositories.worker.WorkerDelivery;
import org.flow.gen.flow.pojos.WorkerDeliveriesObject;

/** 当前执行许可的完整表行。 */
public class WorkerDeliveryEntry extends WorkerDeliveriesObject {
    /** 将受控许可事实转为完整数据库行。
     * @param delivery 非空的完整许可快照，只读取已有身份和技术事实
     * @return 保留传入身份的完整快照，不生成新的业务身份
     */
    public static WorkerDeliveryEntry from(WorkerDelivery delivery) {
        var entry = new WorkerDeliveryEntry();
        entry.companyId = delivery.companyId();
        entry.executionId = delivery.executionId();
        entry.taskRunId = delivery.taskRunId();
        entry.attemptNumber = delivery.attemptNumber();
        entry.deliveryGeneration = delivery.deliveryGeneration();
        entry.ownerId = delivery.ownerId();
        entry.leaseUntilAt = delivery.leaseUntilAt();
        entry.phase = delivery.phase().name();
        entry.resultId = delivery.resultId();
        return entry;
    }
    /**
     * 按原值恢复当前许可，不改变业务尝试或投递代次。
     * @return 保留全部持久事实的独立边界快照
     */
    public WorkerDelivery to() {
        return WorkerDelivery.from(companyId, executionId, taskRunId, attemptNumber, deliveryGeneration,
                ownerId, leaseUntilAt, WorkerDelivery.Phase.valueOf(phase), resultId);
    }
}
