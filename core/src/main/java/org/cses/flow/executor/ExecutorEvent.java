package org.cses.flow.executor;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.queues.event.Event;
import org.cses.flow.queues.annotations.FlowQueue;

import java.util.Objects;

/**
 * Minimal durable signal for one Executor lifecycle transition.
 *
 * <p>The event identifies the tenant and Execution to process. The consumer
 * reloads the Execution's exact Flow reversion and creates a fresh
 * {@link ExecutorContext} for every delivery; no Session, command payload or
 * mutable runtime object crosses the queue.</p>
 */
@FlowQueue(name = ExecutorEvent.QUEUE_NAME, topic = ExecutorEvent.QUEUE_NAME)
public record ExecutorEvent(
    String executionId,
    String companyId,
    EventType eventType,
    String id
) implements Event {

    public static final String QUEUE_NAME = "flow-executor-event";

    /**
     * 首次构建推进消息，生成稳定身份供保存、发布与消费清理使用。
     * @param executionId 非空白原实例身份
     * @param companyId 非空白原租户身份
     * @param eventType 非空推进类型
     * @throws IllegalArgumentException 运行或租户身份为 null 或空白时抛出
     * @throws NullPointerException 推进类型为 null 时抛出
     */
    public ExecutorEvent(String executionId, String companyId, EventType eventType) {
        this(executionId, companyId, eventType, org.paas.common.util.StringUtil.newId());
    }

    /**
     * 校验持久化事件的原身份。
     * @throws IllegalArgumentException 任一身份为 null 或空白时抛出
     * @throws NullPointerException 推进类型为 null 时抛出
     */
    public ExecutorEvent {
        id = requireText(id, org.cses.flow.common.Fields.ID);
        executionId = requireText(executionId, org.cses.flow.common.Fields.EXECUTION_ID);
        companyId = requireText(companyId, org.cses.flow.common.Fields.COMPANY_ID);
        eventType = Objects.requireNonNull(
            eventType,
            "Executor event type"
        );
    }

    /** Builds an event from the identities already persisted on the Execution. */
    public static ExecutorEvent from(
        Execution execution,
        EventType eventType
    ) {
        Objects.requireNonNull(execution, "execution");
        return from(
            execution.id(),
            execution.companyId(),
            eventType
        );
    }

    /**
     * Factory used by queue adapters and tests that already have the persisted
     * identities.
     */
    public static ExecutorEvent from(
        String executionId,
        String companyId,
        EventType eventType
    ) {
        return new ExecutorEvent(
            executionId,
            companyId,
            eventType
        );
    }

    /**
     * Creates the next internal scheduling signal while preserving the
     * aggregate identity; the exact Flow reversion is read from Execution.
     */
    public ExecutorEvent nextUpdate() {
        return from(
            executionId,
            companyId,
            EventType.UPDATED
        );
    }

    @Override
    public String key() {
        return executionId;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public enum EventType {
        CREATED,
        UPDATED,
        TERMINATED
    }
}
