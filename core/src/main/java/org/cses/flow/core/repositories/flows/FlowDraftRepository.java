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
     */
    Optional<FlowDraft> findById(
        DSLContext dsl,
        String companyId,
        String flowId
    );

    /**
     * Locks only an editable, undeleted draft for mutation.
     */
    Optional<FlowDraft> lockById(
        DSLContext dsl,
        String companyId,
        String flowId
    );

    void save(DSLContext dsl, FlowDraft draft);
}
