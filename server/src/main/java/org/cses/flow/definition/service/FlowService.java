package org.cses.flow.definition.service;

import java.util.Comparator;
import org.cses.flow.definition.command.CreateFlowCommand;
import org.cses.flow.definition.model.Flow;
import org.cses.flow.definition.repository.FlowRepository;
import org.cses.flow.shared.IdGenerator;

public final class FlowService {

    private final FlowRepository flowRepository;
    private final FlowValidator flowValidator;
    private final IdGenerator idGenerator;

    public FlowService(
            FlowRepository flowRepository,
            FlowValidator flowValidator,
            IdGenerator idGenerator) {
        this.flowRepository = flowRepository;
        this.flowValidator = flowValidator;
        this.idGenerator = idGenerator;
    }

    public Flow create(CreateFlowCommand command) {
        Flow flow = Flow.draft(
                idGenerator.nextId("flow"),
                command.key(),
                command.name(),
                command.nodes(),
                command.edges());
        return flowRepository.save(flow);
    }

    public Flow deploy(String flowId) {
        Flow flow = flowRepository.findById(flowId)
                .orElseThrow(() -> new IllegalArgumentException("Flow not found: " + flowId));
        flowValidator.validateForDeployment(flow);
        long nextVersion = flowRepository.findByKey(flow.key()).stream()
                .map(Flow::version)
                .filter(version -> version != null)
                .max(Comparator.naturalOrder())
                .orElse(0L) + 1;
        flow.markDeployed(nextVersion);
        return flowRepository.save(flow);
    }
}
