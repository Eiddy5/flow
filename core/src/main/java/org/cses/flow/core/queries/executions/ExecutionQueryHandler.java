package org.cses.flow.core.queries.executions;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.core.services.shared.SessionValidation;
import org.cses.flow.infrastructure.jooq.FlowDatabase;
import org.paas.session.Session;
import org.paas.session.User;
import org.x9.jooq.JOOQ;

import java.util.List;
import java.util.Optional;

@Singleton
public final class ExecutionQueryHandler {

    private final JOOQ jooq;
    private final ExecutionRepository executionRepository;

    @Inject
    public ExecutionQueryHandler(
        @Named(FlowDatabase.DATA_SOURCE_NAME) JOOQ jooq,
        ExecutionRepository executionRepository
    ) {
        this.jooq = jooq;
        this.executionRepository = executionRepository;
    }

    public <S extends Session<U>, U extends User>
        Optional<Execution> execution(
            S session,
            String executionId
        ) {

        return jooq.get(dsl -> executionRepository.findById(
            dsl,
            SessionValidation.requireCompanyId(session),
            executionId
        ));
    }

    public <S extends Session<U>, U extends User>
        List<Execution> executions(S session) {

        return jooq.get(dsl -> executionRepository.findAll(
            dsl,
            SessionValidation.requireCompanyId(session)
        ));
    }
}
