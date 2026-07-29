package org.cses.flow.core.repositories.externaltasks;

import org.cses.flow.core.domains.externaltasks.ExternalTask;
import org.jooq.DSLContext;

import java.util.List;
import java.util.Optional;

public interface ExternalTaskRepository {

    Optional<ExternalTask> findById(
        DSLContext dsl,
        String companyId,
        String externalTaskId
    );

    Optional<ExternalTask> findWaitingByTaskRunId(
        DSLContext dsl,
        String companyId,
        String taskRunId
    );

    List<ExternalTask> findWaiting(
        DSLContext dsl,
        String companyId
    );

    void save(DSLContext dsl, ExternalTask externalTask);
}
