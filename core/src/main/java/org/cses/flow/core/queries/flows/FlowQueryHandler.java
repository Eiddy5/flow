package org.cses.flow.core.queries.flows;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowDraft;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.repositories.flows.FlowDraftRepository;
import org.cses.flow.core.services.shared.SessionValidation;
import org.cses.flow.infrastructure.jooq.FlowDatabase;
import org.paas.session.Session;
import org.paas.session.User;
import org.x9.jooq.JOOQ;

import java.util.List;
import java.util.Optional;

@Singleton
public final class FlowQueryHandler {

    private final JOOQ jooq;
    private final FlowRepository flowRepository;
    private final FlowDraftRepository draftRepository;

    @Inject
    public FlowQueryHandler(
        @Named(FlowDatabase.DATA_SOURCE_NAME) JOOQ jooq,
        FlowRepository flowRepository,
        FlowDraftRepository draftRepository
    ) {
        this.jooq = jooq;
        this.flowRepository = flowRepository;
        this.draftRepository = draftRepository;
    }

    public <S extends Session<U>, U extends User>
        List<FlowDraft> drafts(S session) {

        return jooq.read(dsl -> draftRepository.findAll(
            dsl,
            SessionValidation.requireCompanyId(session)
        ));
    }

    public <S extends Session<U>, U extends User>
        Optional<FlowDraft> draft(S session, String draftId) {

        requireDraftId(draftId);
        return jooq.get(dsl -> draftRepository.findById(
                dsl,
                SessionValidation.requireCompanyId(session),
                draftId
            )
            .filter(draft -> !draft.isDeleted())
        );
    }

    public <S extends Session<U>, U extends User>
        Optional<Flow> flow(
            S session,
            String flowKey,
            long flowVersion
        ) {

        requireFlowKey(flowKey);
        if (flowVersion < 1) {
            throw new IllegalArgumentException(
                "Flow version must be positive"
            );
        }
        return jooq.get(dsl -> flowRepository.findByKey(
            dsl,
            SessionValidation.requireCompanyId(session),
            flowKey,
            flowVersion
        ));
    }

    /**
     * Restores an Execution-bound Flow revision by its technical row id.
     */
    public <S extends Session<U>, U extends User>
        Optional<Flow> flowById(
            S session,
            String flowId,
            long flowVersion
        ) {

        requireFlowKey(flowId);
        if (flowVersion < 1) {
            throw new IllegalArgumentException(
                "Flow version must be positive"
            );
        }
        return jooq.get(dsl -> flowRepository.findById(
            dsl,
            SessionValidation.requireCompanyId(session),
            flowId,
            flowVersion
        ));
    }

    public <S extends Session<U>, U extends User>
        Optional<Flow> latestFlow(S session, String flowKey) {

        requireFlowKey(flowKey);
        return jooq.get(dsl -> flowRepository.findLatestByKey(
                dsl,
                SessionValidation.requireCompanyId(session),
                flowKey
            )
            .filter(flow -> !flow.isDeleted())
        );
    }

    private static void requireFlowKey(String flowKey) {
        if (flowKey == null || flowKey.isBlank()) {
            throw new IllegalArgumentException("Flow key must not be blank");
        }
    }

    private static void requireDraftId(String draftId) {
        if (draftId == null || draftId.isBlank()) {
            throw new IllegalArgumentException("FlowDraft id must not be blank");
        }
    }
}
