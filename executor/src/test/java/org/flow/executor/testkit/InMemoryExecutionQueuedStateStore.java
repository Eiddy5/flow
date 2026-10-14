package org.flow.executor.testkit;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

import org.flow.core.models.executions.Execution;
import org.flow.core.runners.ExecutionQueued;
import org.flow.core.runners.ExecutionQueuedStateStore;
import org.flow.core.runners.TransactionContext;

/**
 * List-backed FIFO {@link ExecutionQueuedStateStore}.
 */
public class InMemoryExecutionQueuedStateStore implements ExecutionQueuedStateStore {
    private final List<ExecutionQueued> queued = new CopyOnWriteArrayList<>();

    @Override
    public void remove(Execution execution) {
        queued.removeIf(entry -> entry.getExecution().getId().equals(execution.getId()));
    }

    @Override
    public void save(TransactionContext txContext, ExecutionQueued executionQueued) {
        queued.add(executionQueued);
    }

    @Override
    public void pop(TransactionContext txContext, String tenantId, String namespace, String flowId, BiConsumer<TransactionContext, Execution> consumer) {
        Optional<ExecutionQueued> next = queued.stream()
            .filter(entry -> entry.getTenantId().equals(tenantId) && entry.getNamespace().equals(namespace) && entry.getFlowId().equals(flowId))
            .min(Comparator.comparing(ExecutionQueued::getDate));

        next.ifPresent(entry ->
        {
            queued.remove(entry);
            consumer.accept(txContext, entry.getExecution());
        });
    }

    /**
     * Every queued execution, in insertion order.
     */
    public List<ExecutionQueued> queued() {
        return List.copyOf(queued);
    }
}
