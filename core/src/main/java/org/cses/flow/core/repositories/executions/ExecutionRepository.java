package org.cses.flow.core.repositories.executions;

import org.cses.flow.core.domains.executions.Execution;
import org.jooq.DSLContext;

import java.util.List;
import java.util.Optional;

/**
 * Aggregate repository for Execution and its ordered TaskRun collection.
 */
public interface ExecutionRepository {

    Optional<Execution> findById(
        DSLContext dsl,
        String companyId,
        String executionId
    );

    List<Execution> findAll(DSLContext dsl, String companyId);

    long count(DSLContext dsl, String companyId);

    void save(DSLContext dsl, Execution execution);
}
