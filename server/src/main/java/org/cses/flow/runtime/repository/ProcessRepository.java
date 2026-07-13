package org.cses.flow.runtime.repository;

import java.util.Optional;
import org.cses.flow.runtime.model.Process;

public interface ProcessRepository {

    Process save(Process process);

    Optional<Process> findById(String processId);
}
