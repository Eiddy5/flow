package org.cses.flow.infrastructure.repositories.worker.entries;

import org.cses.flow.core.repositories.worker.ExecutionMessage;
import org.flow.gen.flow.pojos.ExecutionMessagesObject;
import org.jooq.JSONB;

/** 待消费消息完整表行，正文沿用 PAAS JSON 序列化内容。 */
public class ExecutionMessageEntry extends ExecutionMessagesObject {
    /** 将受控消息事实转为完整数据库行，不生成身份或时间。
     * @param message 非空的完整持久消息，读取而不修改
     * @return 保留传入身份的完整快照，不生成新的业务身份
     */
    public static ExecutionMessageEntry from(ExecutionMessage message) {
        var entry = new ExecutionMessageEntry();
        entry.companyId = message.companyId();
        entry.id = message.id();
        entry.executionId = message.executionId();
        entry.taskRunId = message.taskRunId();
        entry.attemptNumber = message.attemptNumber();
        entry.deliveryGeneration = message.deliveryGeneration();
        entry.kind = message.kind().name();
        entry.payload = JSONB.valueOf(message.payload());
        entry.createdAt = message.createdAt();
        entry.publishedAt = message.publishedAt();
        return entry;
    }
    /**
     * 按原值恢复消息事实，非法正文在协议恢复边界拒绝。
     * @return 保留全部持久事实的独立边界快照
     */
    public ExecutionMessage to() {
        return ExecutionMessage.from(companyId, id, executionId, taskRunId, attemptNumber, deliveryGeneration,
                ExecutionMessage.Kind.valueOf(kind), payload.data(), createdAt, publishedAt);
    }
}
