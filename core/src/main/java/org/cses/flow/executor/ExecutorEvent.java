package org.cses.flow.executor;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.queues.event.DispatchEvent;

import java.util.Objects;

/**
 * Minimal durable signal for one Executor lifecycle transition.
 *
 * <p>The event identifies the tenant and Execution to process. The consumer
 * reloads the Execution's exact Flow reversion and creates a fresh
 * {@link ExecutorContext} for every delivery; no Session, command payload or
 * mutable runtime object crosses the queue.</p>
 */
public record ExecutorEvent(
    String executionId,
    String companyId,
    EventType eventType
) implements DispatchEvent {

    public static final String QUEUE_NAME = "flow-executor-event";

    public ExecutorEvent {
        executionId = requireText(executionId, "Execution id");
        companyId = requireText(companyId, "Company id");
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
