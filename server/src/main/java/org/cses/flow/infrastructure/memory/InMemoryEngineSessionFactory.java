package org.cses.flow.infrastructure.memory;

import java.util.Objects;
import org.cses.flow.definition.session.DefinitionSession;
import org.cses.flow.definition.repository.FlowRepository;
import org.cses.flow.runtime.session.EngineSessionFactory;
import org.cses.flow.runtime.session.EngineTransaction;
import org.cses.flow.runtime.session.RuntimeSession;

public final class InMemoryEngineSessionFactory implements EngineSessionFactory {

    private final FlowRepository flowRepository;
    private final InMemoryRuntimeState runtimeState;

    public InMemoryEngineSessionFactory(
            FlowRepository flowRepository,
            InMemoryRuntimeState runtimeState) {
        this.flowRepository = Objects.requireNonNull(flowRepository, "flowRepository");
        this.runtimeState = Objects.requireNonNull(runtimeState, "runtimeState");
    }

    @Override
    public EngineTransaction openTransaction() {
        return new InMemoryEngineTransaction(runtimeState);
    }

    @Override
    public DefinitionSession openDefinitionSession(EngineTransaction transaction) {
        requireMemoryTransaction(transaction);
        return new InMemoryDefinitionSession(flowRepository);
    }

    @Override
    public RuntimeSession openRuntimeSession(EngineTransaction transaction) {
        return new InMemoryRuntimeSession(requireMemoryTransaction(transaction));
    }

    private InMemoryEngineTransaction requireMemoryTransaction(EngineTransaction transaction) {
        if (!(transaction instanceof InMemoryEngineTransaction memoryTransaction)) {
            throw new IllegalArgumentException("Transaction was not created by the in-memory adapter");
        }
        return memoryTransaction;
    }
}
