package org.cses.flow.core.repositories.flows;

import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowId;
import org.jooq.DSLContext;

import java.util.List;
import java.util.Optional;

public interface FlowRepository {

    /**
     * Restores one exact aggregate state by its persisted identity.
     */
    Optional<Flow> findById(
            DSLContext dsl,
            String companyId,
            String id
    );

    /**
     * Restores one exact deployed Flow version.
     */
    Optional<Flow> findByFlowId(
            DSLContext dsl,
            FlowId flowId
    );

    /**
     * Loads the maximum version for one stable business key without
     * filtering on lifecycle flags.
     * <p>
     * Callers must inspect {@link Flow#deleted()} after selection so a
     * deleted maximum version cannot fall back to an older version.
     */
    Optional<Flow> findLatestByFlowId(
            DSLContext dsl,
            FlowId flowId
    );

    /**
     * Lists all active drafts for one tenant.
     */
    List<Flow> findDrafts(
            DSLContext dsl,
            String companyId
    );

    /**
     * Restores the unique active draft for one logical Flow key.
     */
    Optional<Flow> findDraftByFlowId(
            DSLContext dsl,
            FlowId flowId
    );

    void save(DSLContext dsl, Flow flow);
}
