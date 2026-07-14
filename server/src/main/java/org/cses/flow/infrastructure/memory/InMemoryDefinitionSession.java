package org.cses.flow.infrastructure.memory;

import java.util.Objects;
import org.cses.flow.definition.model.Flow;
import org.cses.flow.definition.session.DefinitionSession;
import org.cses.flow.definition.repository.FlowRepository;

public final class InMemoryDefinitionSession implements DefinitionSession {

    private final FlowRepository repository;

    InMemoryDefinitionSession(FlowRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public Flow resolveStartFlow(String requestedFlowId) {
        Flow requested = repository.findById(requestedFlowId)
                .orElseThrow(() -> new IllegalArgumentException("Flow not found: " + requestedFlowId));
        return repository.findLatestDeployed(requested.key())
                .orElseThrow(() -> new IllegalStateException(
                        "No deployed Flow exists for key: " + requested.key()));
    }

    @Override
    public Flow loadBoundFlow(String flowId) {
        return repository.findById(flowId)
                .orElseThrow(() -> new IllegalStateException("Bound Flow not found: " + flowId));
    }
}
