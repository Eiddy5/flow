package org.cses.flow.core.services.executions.queries;

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
public class ExecutionQueryHandler {

    private JOOQ jooq;
    private ExecutionRepository executionRepository;

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

    /**
     * Resolves an accessible Execution and loads its entire derivation tree.
     * @param session tenant-scoped caller
     * @param executionId any member of the tree
     * @return independently restored snapshots in creation order
     * @throws org.cses.flow.core.exceptions.WorkflowException when the member is not accessible
     */
    public <S extends Session<U>, U extends User> List<Execution> lineage(S session, String executionId) {
        String companyId = SessionValidation.requireCompanyId(session);
        return jooq.get(dsl -> {
            Execution member = executionRepository.findById(dsl, companyId, executionId)
                    .orElseThrow(() -> new org.cses.flow.core.exceptions.WorkflowException(
                            "Execution does not exist: " + executionId));
            return executionRepository.findByOriginId(dsl, companyId, member.origin().originId());
        });
    }

}
