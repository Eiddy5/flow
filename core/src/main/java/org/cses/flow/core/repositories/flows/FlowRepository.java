package org.cses.flow.core.repositories.flows;

import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowId;
import org.jooq.DSLContext;

import java.util.List;
import java.util.Optional;

public interface FlowRepository {

    /**
     * Restores one exact aggregate state by its persisted identity.
     *
     * @param dsl non-null caller-owned database context used only for reads
     * @param companyId non-blank tenant boundary
     * @param id non-blank database row identifier
     * @return a detached Flow when the tenant and row exist, otherwise empty
     */
    Optional<Flow> findById(
            DSLContext dsl,
            String companyId,
            String id
    );

    /**
     * Restores one exact deployed Flow version.
     *
     * @param dsl non-null caller-owned database context used only for reads
     * @param flowId non-null tenant, key and positive-version selector
     * @return the detached deployed version, including a deleted version, or
     *         empty when no exact row exists
     * @throws IllegalArgumentException when the selector has no version
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
     *
     * @param dsl non-null caller-owned database context used only for reads
     * @param flowId non-null tenant and key selector without a version
     * @return the detached maximum deployed version, including a deleted
     *         version, or empty when none exists
     * @throws IllegalArgumentException when the selector contains a version
     */
    Optional<Flow> findLatestByFlowId(
            DSLContext dsl,
            FlowId flowId
    );

    /**
     * Lists the latest active draft for each logical Flow in one tenant.
     *
     * @param dsl non-null caller-owned database context used only for reads
     * @param companyId non-blank tenant boundary
     * @return an unmodifiable list of detached active drafts, or an empty list
     *         when the tenant has none
     */
    List<Flow> findDrafts(
            DSLContext dsl,
            String companyId
    );

    /**
     * Restores the latest draft when that version remains active.
     *
     * @param dsl non-null caller-owned database context used only for reads
     * @param flowId non-null tenant and key selector without a version
     * @return the detached latest draft, or empty when absent or when its
     *         latest draft version is deleted
     * @throws IllegalArgumentException when the selector contains a version
     */
    Optional<Flow> findDraftByFlowId(
            DSLContext dsl,
            FlowId flowId
    );

    /**
     * Appends one Flow record using the next version for its tenant and key.
     * The supplied Flow provides definition and audit facts; the Repository
     * assigns the row id and version and returns the persisted snapshot.
     *
     * @param dsl caller-owned transaction context; must not be {@code null}
     * @param flow Flow state to append; must not be {@code null} and is read
     *        without modification
     * @return detached Flow snapshot containing the assigned row id and version
     * @throws org.cses.flow.core.exceptions.WorkflowException when the database
     *         rejects the new row or its Task snapshot
     * @throws NullPointerException when {@code dsl} or {@code flow} is null
     */
    Flow save(DSLContext dsl, Flow flow);
}
