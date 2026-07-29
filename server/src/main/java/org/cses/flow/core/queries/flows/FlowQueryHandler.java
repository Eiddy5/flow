package org.cses.flow.core.queries.flows;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowWithSource;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.repositories.flows.FlowWithSourceRepository;
import org.cses.flow.core.services.shared.SessionValidation;
import org.paas.session.Session;
import org.paas.session.User;
import org.x9.jooq.JOOQ;

import java.util.Optional;

@Singleton
public final class FlowQueryHandler {

    private final JOOQ jooq;
    private final FlowRepository flowRepository;
    private final FlowWithSourceRepository sourceRepository;

    @Inject
    public FlowQueryHandler(
        JOOQ jooq,
        FlowRepository flowRepository,
        FlowWithSourceRepository sourceRepository
    ) {
        this.jooq = jooq;
        this.flowRepository = flowRepository;
        this.sourceRepository = sourceRepository;
    }

    public <S extends Session<U>, U extends User>
        Optional<FlowWithSource> source(S session, String flowId) {

        requireFlowId(flowId);
        return jooq.get(dsl -> sourceRepository.findById(
            dsl,
            SessionValidation.requireCompanyId(session),
            flowId
        ));
    }

    public <S extends Session<U>, U extends User>
        Optional<Flow> flow(
            S session,
            String flowId,
            long reversion
        ) {

        requireFlowId(flowId);
        if (reversion < 1) {
            throw new IllegalArgumentException(
                "Flow reversion must be positive"
            );
        }
        return jooq.get(dsl -> flowRepository.findById(
            dsl,
            SessionValidation.requireCompanyId(session),
            flowId,
            reversion
        ));
    }

    public <S extends Session<U>, U extends User>
        Optional<Flow> latestFlow(S session, String flowId) {

        requireFlowId(flowId);
        return jooq.get(dsl -> flowRepository.findLatest(
            dsl,
            SessionValidation.requireCompanyId(session),
            flowId
        ));
    }

    private static void requireFlowId(String flowId) {
        if (flowId == null || flowId.isBlank()) {
            throw new IllegalArgumentException("Flow id must not be blank");
        }
    }
}
