package org.cses.flow.core.queries.externaltasks;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.externaltasks.ExternalTask;
import org.cses.flow.core.repositories.externaltasks.ExternalTaskRepository;
import org.cses.flow.core.services.shared.SessionValidation;
import org.cses.flow.infrastructure.jooq.FlowDatabase;
import org.paas.session.Session;
import org.paas.session.User;
import org.x9.jooq.JOOQ;

import java.util.List;
import java.util.Optional;

@Singleton
public final class ExternalTaskQueryHandler {

    private final JOOQ jooq;
    private final ExternalTaskRepository externalTaskRepository;

    @Inject
    public ExternalTaskQueryHandler(
        @Named(FlowDatabase.DATA_SOURCE_NAME) JOOQ jooq,
        ExternalTaskRepository externalTaskRepository
    ) {
        this.jooq = jooq;
        this.externalTaskRepository = externalTaskRepository;
    }

    public <S extends Session<U>, U extends User>
        Optional<ExternalTask> externalTask(
            S session,
            String externalTaskId
        ) {

        return jooq.get(dsl -> externalTaskRepository.findById(
            dsl,
            SessionValidation.requireCompanyId(session),
            externalTaskId
        ));
    }

    public <S extends Session<U>, U extends User>
        Optional<ExternalTask> waitingForTaskRun(
            S session,
            String taskRunId
        ) {

        return jooq.get(dsl ->
            externalTaskRepository.findWaitingByTaskRunId(
                dsl,
                SessionValidation.requireCompanyId(session),
                taskRunId
            )
        );
    }

    public <S extends Session<U>, U extends User>
        List<ExternalTask> waitingTasks(S session) {

        return jooq.get(dsl -> externalTaskRepository.findWaiting(
            dsl,
            SessionValidation.requireCompanyId(session)
        ));
    }
}
