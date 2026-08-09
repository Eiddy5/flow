package org.cses.flow.core.repositories.flows.memory;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.flows.FlowDraft;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.repositories.flows.FlowDraftRepository;
import org.cses.flow.infrastructure.memory.InMemoryTransactionManager;
import org.cses.flow.infrastructure.memory.InMemoryTransactionalResource;
import org.jooq.DSLContext;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test-only process-local FlowDraft repository.
 */
@Singleton
@Requires(property = "flow.memory.enabled", value = "true")
public final class InMemoryFlowDraftRepository
    implements FlowDraftRepository, InMemoryTransactionalResource {

    private final Map<DraftIdentity, FlowDraft> drafts =
        new ConcurrentHashMap<>();

    public InMemoryFlowDraftRepository() {
    }

    @Inject
    public InMemoryFlowDraftRepository(
        InMemoryTransactionManager transactionManager
    ) {
        transactionManager.register(this);
    }

    @Override
    public List<FlowDraft> findAll(
        DSLContext dsl,
        String companyId
    ) {
        return drafts.values().stream()
            .filter(draft -> draft.companyId().equals(companyId))
            .filter(draft -> !draft.isDeleted())
            .map(FlowDraft::copy)
            .sorted(
                Comparator.comparingLong(FlowDraft::updatedAt)
                    .reversed()
                    .thenComparing(FlowDraft::id)
            )
            .toList();
    }

    @Override
    public Optional<FlowDraft> findById(
        DSLContext dsl,
        String companyId,
        String flowId
    ) {
        FlowDraft draft = drafts.get(
            new DraftIdentity(companyId, flowId)
        );
        return draft == null
            ? Optional.empty()
            : Optional.of(draft.copy());
    }

    @Override
    public Optional<FlowDraft> lockById(
        DSLContext dsl,
        String companyId,
        String flowId
    ) {
        return active(companyId, flowId);
    }

    @Override
    public synchronized void save(
        DSLContext dsl,
        FlowDraft draft
    ) {
        DraftIdentity identity = new DraftIdentity(
            draft.companyId(),
            draft.id()
        );
        FlowDraft stored = drafts.get(identity);
        if (stored == null) {
            if (!draft.hasLockVersion(0) || draft.isDeleted()) {
                throw conflict(draft, -1);
            }
        } else if (!draft.hasLockVersion(stored.lockVersion() + 1)) {
            throw conflict(draft, stored.lockVersion());
        } else {
            requireAllowedChange(stored, draft);
        }
        drafts.put(identity, draft.copy());
    }

    @Override
    public synchronized Object snapshot() {
        Map<DraftIdentity, FlowDraft> snapshot = new HashMap<>();
        drafts.forEach((identity, draft) ->
            snapshot.put(identity, draft.copy())
        );
        return snapshot;
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized void restore(Object snapshot) {
        drafts.clear();
        ((Map<DraftIdentity, FlowDraft>) snapshot).forEach(
            (identity, draft) -> drafts.put(identity, draft.copy())
        );
    }

    private Optional<FlowDraft> active(
        String companyId,
        String flowId
    ) {
        FlowDraft draft = drafts.get(
            new DraftIdentity(companyId, flowId)
        );
        return draft == null
            || draft.isDeleted()
            ? Optional.empty()
            : Optional.of(draft.copy());
    }

    private static WorkflowException conflict(
        FlowDraft draft,
        long storedVersion
    ) {
        return new WorkflowException(
            "FlowDraft lock conflict for " + draft.id()
                + ": stored " + storedVersion
                + ", attempted " + draft.lockVersion()
        );
    }

    private static void requireAllowedChange(
        FlowDraft stored,
        FlowDraft attempted
    ) {
        boolean identityAndCreationAuditMatch =
            stored.identifiedBy(attempted.identifier())
                && stored.companyId().equals(attempted.companyId())
                && stored.creator().equals(attempted.creator())
                && stored.createdAt() == attempted.createdAt();
        if (!identityAndCreationAuditMatch
            || stored.isDeleted()
            || (
                attempted.isDeleted()
                    && !stored.raw().equals(attempted.raw())
            )) {
            throw new WorkflowException(
                "FlowDraft update violates lifecycle invariants: "
                    + attempted.id()
            );
        }
    }

    private static final class DraftIdentity {

        private final String companyId;
        private final String flowId;

        private DraftIdentity(String companyId, String flowId) {
            this.companyId = companyId;
            this.flowId = flowId;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            if (!(value instanceof DraftIdentity other)) {
                return false;
            }
            return Objects.equals(companyId, other.companyId)
                && Objects.equals(flowId, other.flowId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(companyId, flowId);
        }
    }
}
