package org.cses.flow.definition.repository;

import java.util.List;
import java.util.Optional;
import org.cses.flow.definition.model.Flow;

public interface FlowRepository {

    Flow save(Flow flow);

    Optional<Flow> findById(String flowId);

    Optional<Flow> findLatestDeployed(String flowKey);

    List<Flow> findByKey(String flowKey);
}
