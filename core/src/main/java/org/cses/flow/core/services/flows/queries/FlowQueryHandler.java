package org.cses.flow.core.services.flows.queries;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowId;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.services.shared.SessionValidation;
import org.cses.flow.infrastructure.jooq.FlowDatabase;
import org.paas.session.Session;
import org.paas.session.User;
import org.x9.jooq.JOOQ;

import java.util.List;
import java.util.Optional;

@Singleton
public class FlowQueryHandler {

    JOOQ jooq;
    FlowRepository flowRepository;

    @Inject
    public FlowQueryHandler(
            @Named(FlowDatabase.DATA_SOURCE_NAME) JOOQ jooq,
            FlowRepository flowRepository
    ) {
        this.jooq = jooq;
        this.flowRepository = flowRepository;
    }

    public <S extends Session<U>, U extends User>
    List<Flow> drafts(S session) {

        return jooq.read(dsl -> flowRepository.findDrafts(
                dsl,
                SessionValidation.requireCompanyId(session)
        ));
    }

    public <S extends Session<U>, U extends User>
    Optional<Flow> draft(S session, String flowKey) {

        String normalizedFlowKey = requireFlowKey(flowKey);
        return jooq.get(dsl -> flowRepository.findDraftByFlowId(
                        dsl,
                        FlowId.from(
                                SessionValidation.requireCompanyId(session),
                                normalizedFlowKey
                        )
                )
        );
    }

    public <S extends Session<U>, U extends User>
    Optional<Flow> flow(
            S session,
            String flowKey,
            long flowVersion
    ) {

        String normalizedFlowKey = requireFlowKey(flowKey);
        if (flowVersion < 1) {
            throw new IllegalArgumentException(
                    "Flow version must be positive"
            );
        }
        return jooq.get(dsl -> flowRepository.findByFlowId(
                dsl,
                FlowId.from(
                        SessionValidation.requireCompanyId(session),
                        normalizedFlowKey,
                        flowVersion
                )
        ));
    }

    public <S extends Session<U>, U extends User>
    Optional<Flow> latestFlow(S session, String flowKey) {

        String normalizedFlowKey = requireFlowKey(flowKey);
        return jooq.get(dsl -> flowRepository.findLatestByFlowId(
                dsl,
                FlowId.from(
                        SessionValidation.requireCompanyId(session),
                        normalizedFlowKey
                )
        ).filter(flow -> !flow.deleted()));
    }

    /**
     * Resolves the unique editable draft by its stable Flow key.
     */
    public <S extends Session<U>, U extends User>
    Optional<Flow> draftByFlowKey(S session, String flowKey) {
        return draft(session, flowKey);
    }

    private static String requireFlowKey(String flowKey) {
        if (flowKey == null || flowKey.isBlank()) {
            throw new IllegalArgumentException("Flow key must not be blank");
        }
        return flowKey.trim();
    }

}
