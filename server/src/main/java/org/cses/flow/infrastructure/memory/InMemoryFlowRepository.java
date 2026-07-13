package org.cses.flow.infrastructure.memory;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.cses.flow.definition.model.Flow;
import org.cses.flow.definition.model.FlowState;
import org.cses.flow.definition.repository.FlowRepository;

public final class InMemoryFlowRepository implements FlowRepository {

    private final ConcurrentMap<String, Flow> flows = new ConcurrentHashMap<>();

    @Override
    public Flow save(Flow flow) {
        flows.put(flow.id(), flow);
        return flow;
    }

    @Override
    public Optional<Flow> findById(String flowId) {
        return Optional.ofNullable(flows.get(flowId));
    }

    @Override
    public Optional<Flow> findLatestDeployed(String flowKey) {
        return flows.values().stream()
                .filter(flow -> flow.key().equals(flowKey))
                .filter(flow -> flow.state() == FlowState.DEPLOYED)
                .max(Comparator.comparing(Flow::version));
    }

    @Override
    public List<Flow> findByKey(String flowKey) {
        return flows.values().stream()
                .filter(flow -> flow.key().equals(flowKey))
                .sorted(Comparator.comparing(Flow::createdAt))
                .toList();
    }
}
