package org.cses.flow.core.repositories.flows.memory;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.flows.FlowWithSource;
import org.cses.flow.core.exceptions.shared.WorkflowException;
import org.cses.flow.core.repositories.flows.FlowWithSourceRepository;
import org.cses.flow.infrastructure.memory.InMemoryTransactionManager;
import org.cses.flow.infrastructure.memory.InMemoryTransactionalResource;
import org.jooq.DSLContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test-only process-local editable Flow source repository.
 */
@Singleton
@Requires(property = "flow.memory.enabled", value = "true")
public final class InMemoryFlowWithSourceRepository
    implements FlowWithSourceRepository, InMemoryTransactionalResource {

    private final Map<SourceIdentity, FlowWithSource> sources =
        new ConcurrentHashMap<>();

    public InMemoryFlowWithSourceRepository() {
    }

    @Inject
    public InMemoryFlowWithSourceRepository(
        InMemoryTransactionManager transactionManager
    ) {
        transactionManager.register(this);
    }

    @Override
    public Optional<FlowWithSource> findById(
        DSLContext dsl,
        String companyId,
        String flowId
    ) {
        return active(companyId, flowId);
    }

    @Override
    public Optional<FlowWithSource> lockById(
        DSLContext dsl,
        String companyId,
        String flowId
    ) {
        return active(companyId, flowId);
    }

    @Override
    public synchronized void save(
        DSLContext dsl,
        FlowWithSource source
    ) {
        SourceIdentity identity = new SourceIdentity(
            source.companyId(),
            source.id()
        );
        FlowWithSource stored = sources.get(identity);
        if (stored == null) {
            if (source.lockVersion() != 0) {
                throw conflict(source, -1);
            }
        } else if (source.lockVersion() != stored.lockVersion() + 1) {
            throw conflict(source, stored.lockVersion());
        }
        sources.put(identity, source.copy());
    }

    @Override
    public synchronized Object snapshot() {
        Map<SourceIdentity, FlowWithSource> snapshot = new HashMap<>();
        sources.forEach((identity, source) ->
            snapshot.put(identity, source.copy())
        );
        return snapshot;
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized void restore(Object snapshot) {
        sources.clear();
        ((Map<SourceIdentity, FlowWithSource>) snapshot).forEach(
            (identity, source) -> sources.put(identity, source.copy())
        );
    }

    private Optional<FlowWithSource> active(
        String companyId,
        String flowId
    ) {
        FlowWithSource source = sources.get(
            new SourceIdentity(companyId, flowId)
        );
        return source == null || source.isDiscarded()
            ? Optional.empty()
            : Optional.of(source.copy());
    }

    private static WorkflowException conflict(
        FlowWithSource source,
        long storedVersion
    ) {
        return new WorkflowException(
            "FlowWithSource lock conflict for " + source.id()
                + ": stored " + storedVersion
                + ", attempted " + source.lockVersion()
        );
    }

    private record SourceIdentity(String companyId, String flowId) {
    }
}
