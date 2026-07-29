package org.cses.flow.core.repositories.flows;

import org.cses.flow.core.domains.flows.Flow;
import org.jooq.DSLContext;

import java.util.Optional;

public interface FlowRepository {

    Optional<Flow> findById(
        DSLContext dsl,
        String companyId,
        String flowId,
        long reversion
    );

    Optional<Flow> findLatest(
        DSLContext dsl,
        String companyId,
        String flowId
    );

    void save(DSLContext dsl, Flow flow);
}
