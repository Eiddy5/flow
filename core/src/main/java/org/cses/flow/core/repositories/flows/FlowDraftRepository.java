package org.cses.flow.core.repositories.flows;

import org.cses.flow.core.domains.flows.FlowDraft;
import org.jooq.DSLContext;

import java.util.List;
import java.util.Optional;

/**
 * Repository for the unique editable draft of a logical Flow.
 */
public interface FlowDraftRepository {

    /**
     * Lists only editable, undeleted drafts for one company.
     */
    List<FlowDraft> findAll(
        DSLContext dsl,
        String companyId
    );

    /**
     * Loads one exact draft, including a logically deleted draft.
     * This is an adapter-level lookup by the database row marker.
     */
    Optional<FlowDraft> findById(
        DSLContext dsl,
        String companyId,
        String rowId
    );

    /**
     * Loads the current editable draft associated with a stable Flow key.
     */
    Optional<FlowDraft> findByFlowKey(
        DSLContext dsl,
        String companyId,
        String flowKey
    );

    /**
     * Locks only an editable, undeleted draft for mutation by its business key.
     */
    Optional<FlowDraft> lockByFlowKey(
        DSLContext dsl,
        String companyId,
        String flowKey
    );

    /**
     * Internal exact-row lock for persistence tests and adapter-only recovery.
     * Business callers must use {@link #lockByFlowKey(DSLContext, String, String)}.
     */
    Optional<FlowDraft> lockById(
        DSLContext dsl,
        String companyId,
        String rowId
    );

    void save(DSLContext dsl, FlowDraft draft);
}
