package org.cses.flow.core.repositories.flows;

import org.cses.flow.core.domains.flows.Flow;
import org.jooq.DSLContext;

import java.util.Optional;

public interface FlowRepository {

    /**
     * Loads one exact reversion, including a logically deleted reversion.
     */
    Optional<Flow> findById(
        DSLContext dsl,
        String companyId,
        String flowId,
        long reversion
    );

    /**
     * Loads the maximum reversion without filtering on lifecycle flags.
     *
     * Callers must inspect {@link Flow#isDeleted()} after selection so a
     * deleted maximum reversion cannot fall back to an older reversion.
     */
    Optional<Flow> findLatest(
        DSLContext dsl,
        String companyId,
        String flowId
    );

    void save(DSLContext dsl, Flow flow);
}
