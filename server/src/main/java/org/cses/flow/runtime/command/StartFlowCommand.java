package org.cses.flow.runtime.command;

import java.util.Map;
import java.util.Objects;
import org.cses.flow.runtime.context.FlowContext;
import org.cses.flow.runtime.execution.ExecutionOperationFactory;
import org.cses.flow.runtime.model.Executor;
import org.cses.flow.runtime.model.Process;
import org.cses.flow.runtime.repository.ProcessRepository;
import org.cses.flow.shared.IdGenerator;

public final class StartFlowCommand implements Command<Process> {

    private final IdGenerator idGenerator;
    private final ProcessRepository processRepository;
    private final ExecutionOperationFactory operationFactory;

    public StartFlowCommand(
            IdGenerator idGenerator,
            ProcessRepository processRepository,
            ExecutionOperationFactory operationFactory) {
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.processRepository = Objects.requireNonNull(processRepository, "processRepository");
        this.operationFactory = Objects.requireNonNull(operationFactory, "operationFactory");
    }

    @Override
    public Process execute(FlowContext flowContext) {
        Process process = new Process(
                idGenerator.nextId("process"),
                flowContext.flow(),
                Map.of());
        Executor rootExecutor = process.createRootExecutor(
                idGenerator.nextId("executor"),
                flowContext.flow().startNode());
        processRepository.save(process);
        flowContext.setProcess(process);
        flowContext.executionQueue().plan(operationFactory.continueExecutor(rootExecutor.id()));
        return process;
    }
}
