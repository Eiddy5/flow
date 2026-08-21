package org.cses.flow.core.repositories.flows;

import org.cses.flow.core.domains.flows.Flow;
import org.jooq.DSLContext;

import java.util.Optional;

public interface FlowRepository {

    /**
     * Loads one exact Flow reversion by its stable business key.
     */
    Optional<Flow> findByKey(
            DSLContext dsl,
            String companyId,
            String flowKey,
            long flowVersion
    );

    /**
     * Loads the maximum version for one stable business key without
     * filtering on lifecycle flags.
     * <p>
     * Callers must inspect {@link Flow#isDeleted()} after selection so a
     * deleted maximum version cannot fall back to an older version.
     */
    Optional<Flow> findLatestByKey(
            DSLContext dsl,
            String companyId,
            String flowKey
    );

    void save(DSLContext dsl, Flow flow);
}
