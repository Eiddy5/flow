package org.cses.flow.core.repositories.flows.memory;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.infrastructure.memory.InMemoryTransactionManager;
import org.cses.flow.infrastructure.memory.InMemoryTransactionalResource;
import org.jooq.DSLContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test-only process-local deployed Flow repository.
 */
@Singleton
@Requires(property = "flow.memory.enabled", value = "true")
public final class InMemoryFlowRepository
    implements FlowRepository, InMemoryTransactionalResource {

    private final Map<FlowIdentity, Flow> flows =
        new ConcurrentHashMap<>();
    private final Map<FlowKey, String> flowIds =
        new ConcurrentHashMap<>();

    public InMemoryFlowRepository() {
    }

    @Inject
    public InMemoryFlowRepository(
        InMemoryTransactionManager transactionManager
    ) {
        transactionManager.register(this);
    }

    @Override
    public Optional<Flow> findById(
        DSLContext dsl,
        String companyId,
        String flowId,
        long reversion
    ) {
        Flow flow = flows.get(
            new FlowIdentity(companyId, flowId, reversion)
        );
        return flow == null
            ? Optional.empty()
            : Optional.of(flow.copy());
    }

    @Override
    public Optional<Flow> findLatest(
        DSLContext dsl,
        String companyId,
        String flowId
    ) {
        return flows.entrySet().stream()
            .filter(entry ->
                entry.getKey().companyId.equals(companyId)
                    && entry.getKey().flowId.equals(flowId)
            )
            .map(Map.Entry::getValue)
            .max(java.util.Comparator.comparingLong(Flow::reversion))
            .map(Flow::copy);
    }

    @Override
    public synchronized void save(DSLContext dsl, Flow flow) {
        FlowKey key = new FlowKey(flow.companyId(), flow.key());
        String existingId = flowIds.putIfAbsent(key, flow.id());
        if (existingId != null && !flow.identifiedBy(existingId)) {
            throw new WorkflowException(
                "Flow key already exists: " + flow.key()
            );
        }

        FlowIdentity identity = new FlowIdentity(
            flow.companyId(),
            flow.id(),
            flow.reversion()
        );
        Flow existing = flows.get(identity);
        if (existing != null) {
            requireDeleteOnlyChange(existing, flow);
            flows.put(identity, flow.copy());
            return;
        }

        long latest = findLatest(
            dsl,
            flow.companyId(),
            flow.id()
        ).map(Flow::reversion).orElse(0L);
        if (flow.reversion() != latest + 1) {
            throw new WorkflowException(
                "Flow reversion conflict for " + flow.id()
                    + ": stored latest " + latest
                    + ", attempted " + flow.reversion()
            );
        }
        Flow latestFlow = latest == 0
            ? null
            : flows.get(new FlowIdentity(
                flow.companyId(),
                flow.id(),
                latest
            ));
        if (flow.isDeleted()
            || (
                latestFlow != null
                    && latestFlow.isDeleted()
            )) {
            throw new WorkflowException(
                "A new Flow reversion must be undeleted and follow an "
                    + "undeleted latest Flow: " + flow.id()
            );
        }
        flows.put(identity, flow.copy());
    }

    private static void requireDeleteOnlyChange(
        Flow stored,
        Flow attempted
    ) {
        if (stored.isDeleted() || !attempted.isDeleted()) {
            throw new WorkflowException(
                "Existing Flow reversion may only transition from "
                    + "deleted=false to deleted=true: " + attempted.id()
                    + ":" + attempted.reversion()
            );
        }
        boolean immutableStateMatches =
            stored.identifiedBy(attempted.identifier())
                && stored.companyId().equals(attempted.companyId())
                && stored.key().equals(attempted.key())
                && stored.reversion() == attempted.reversion()
                && stored.description().equals(attempted.description())
                && stored.inputs().equals(attempted.inputs())
                && stored.outputs().equals(attempted.outputs())
                && stored.tasks().equals(attempted.tasks())
                && stored.creator().equals(attempted.creator())
                && stored.createdAt() == attempted.createdAt();
        if (!immutableStateMatches) {
            throw new WorkflowException(
                "Deleting a Flow must not change its deployed definition: "
                    + attempted.id() + ":" + attempted.reversion()
            );
        }
    }

    @Override
    public synchronized Object snapshot() {
        Map<FlowIdentity, Flow> flowSnapshot = new HashMap<>();
        flows.forEach((identity, flow) ->
            flowSnapshot.put(identity, flow.copy())
        );
        return new RepositoryState(
            flowSnapshot,
            new HashMap<>(flowIds)
        );
    }

    @Override
    public synchronized void restore(Object snapshot) {
        RepositoryState state = (RepositoryState) snapshot;
        flows.clear();
        state.flows.forEach((identity, flow) ->
            flows.put(identity, flow.copy())
        );
        flowIds.clear();
        flowIds.putAll(state.flowIds);
    }

    private static final class RepositoryState {

        private final Map<FlowIdentity, Flow> flows;
        private final Map<FlowKey, String> flowIds;

        private RepositoryState(
            Map<FlowIdentity, Flow> flows,
            Map<FlowKey, String> flowIds
        ) {
            this.flows = flows;
            this.flowIds = flowIds;
        }
    }

    private static final class FlowIdentity {

        private final String companyId;
        private final String flowId;
        private final long reversion;

        private FlowIdentity(
            String companyId,
            String flowId,
            long reversion
        ) {
            this.companyId = companyId;
            this.flowId = flowId;
            this.reversion = reversion;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            if (!(value instanceof FlowIdentity other)) {
                return false;
            }
            return reversion == other.reversion
                && Objects.equals(companyId, other.companyId)
                && Objects.equals(flowId, other.flowId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(companyId, flowId, reversion);
        }
    }

    private static final class FlowKey {

        private final String companyId;
        private final String flowKey;

        private FlowKey(String companyId, String flowKey) {
            this.companyId = companyId;
            this.flowKey = flowKey;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            if (!(value instanceof FlowKey other)) {
                return false;
            }
            return Objects.equals(companyId, other.companyId)
                && Objects.equals(flowKey, other.flowKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(companyId, flowKey);
        }
    }
}
