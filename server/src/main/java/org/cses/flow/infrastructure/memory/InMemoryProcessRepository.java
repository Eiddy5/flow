package org.cses.flow.infrastructure.memory;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.cses.flow.runtime.model.Process;
import org.cses.flow.runtime.repository.ProcessRepository;

public final class InMemoryProcessRepository implements ProcessRepository {

    private final ConcurrentMap<String, Process> processes = new ConcurrentHashMap<>();

    @Override
    public Process save(Process process) {
        processes.put(process.id(), process);
        return process;
    }

    @Override
    public Optional<Process> findById(String processId) {
        return Optional.ofNullable(processes.get(processId));
    }
}
