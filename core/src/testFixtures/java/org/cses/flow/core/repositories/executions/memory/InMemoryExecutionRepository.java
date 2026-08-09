package org.cses.flow.core.repositories.executions.memory;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import io.micronaut.context.annotation.Requires;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.jooq.DSLContext;
import org.cses.flow.infrastructure.memory.InMemoryTransactionManager;
import org.cses.flow.infrastructure.memory.InMemoryTransactionalResource;

import java.util.Map;
import java.util.Objects;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Test-only process-local Execution aggregate repository. */
@Singleton
@Requires(property = "flow.memory.enabled", value = "true")
public final class InMemoryExecutionRepository
    implements ExecutionRepository, InMemoryTransactionalResource {

    private final Map<ExecutionIdentity, Execution> executions =
        new ConcurrentHashMap<>();

    public InMemoryExecutionRepository() {
    }

    @Inject
    public InMemoryExecutionRepository(
        InMemoryTransactionManager transactionManager
    ) {
        transactionManager.register(this);
    }

    @Override
    public Optional<Execution> findById(
        DSLContext dsl,
        String companyId,
        String executionId
    ) {
        Execution execution = executions.get(
            new ExecutionIdentity(companyId, executionId)
        );
        return execution == null
            ? Optional.empty()
            : Optional.of(execution.copy());
    }

    @Override
    public Optional<Execution> lockById(
        DSLContext dsl,
        String companyId,
        String executionId
    ) {
        return findById(dsl, companyId, executionId);
    }

    @Override
    public List<Execution> findAll(
        DSLContext dsl,
        String companyId
    ) {
        return executions.entrySet().stream()
            .filter(entry ->
                entry.getKey().companyId.equals(companyId)
            )
            .map(entry -> entry.getValue().copy())
            .sorted(java.util.Comparator.comparing(Execution::id))
            .toList();
    }

    @Override
    public long count(DSLContext dsl, String companyId) {
        return executions.keySet().stream()
            .filter(identity -> identity.companyId.equals(companyId))
            .count();
    }

    @Override
    public void save(DSLContext dsl, Execution execution) {
        executions.put(
            new ExecutionIdentity(
                execution.companyId(),
                execution.id()
            ),
            execution.copy()
        );
    }

    @Override
    public Object snapshot() {
        Map<ExecutionIdentity, Execution> snapshot = new java.util.HashMap<>();
        executions.forEach((identity, execution) ->
            snapshot.put(identity, execution.copy())
        );
        return snapshot;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void restore(Object snapshot) {
        executions.clear();
        ((Map<ExecutionIdentity, Execution>) snapshot)
            .forEach((identity, execution) ->
                executions.put(identity, execution.copy())
            );
    }

    private static final class ExecutionIdentity {

        private final String companyId;
        private final String executionId;

        private ExecutionIdentity(
            String companyId,
            String executionId
        ) {
            this.companyId = companyId;
            this.executionId = executionId;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            if (!(value instanceof ExecutionIdentity other)) {
                return false;
            }
            return Objects.equals(companyId, other.companyId)
                && Objects.equals(executionId, other.executionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(companyId, executionId);
        }
    }
}
