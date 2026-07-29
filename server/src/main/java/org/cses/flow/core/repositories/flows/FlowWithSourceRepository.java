package org.cses.flow.core.repositories.flows;

import org.cses.flow.core.domains.flows.FlowWithSource;
import org.jooq.DSLContext;

import java.util.Optional;

/**
 * Repository for the unique editable source of a logical Flow.
 */
public interface FlowWithSourceRepository {

    Optional<FlowWithSource> findById(
        DSLContext dsl,
        String companyId,
        String flowId
    );

    Optional<FlowWithSource> lockById(
        DSLContext dsl,
        String companyId,
        String flowId
    );

    void save(DSLContext dsl, FlowWithSource source);
}
