package org.cses.flow.core.handlers.flows;

import org.cses.flow.core.domains.flows.ActorRef;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowDraft;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.repositories.flows.FlowDraftRepository;
import org.jooq.DSLContext;
import org.paas.session.Session;
import org.paas.session.User;

public final class FlowHandlerSupport {

    private FlowHandlerSupport() {
    }

    public static Flow requireLatestFlow(
        FlowRepository repository,
        DSLContext dsl,
        String companyId,
        String flowId
    ) {
        return repository.findLatest(dsl, companyId, flowId)
            .orElseThrow(() ->
                new WorkflowException("Flow does not exist: " + flowId)
            );
    }

    public static Flow requireFlow(
        FlowRepository repository,
        DSLContext dsl,
        String companyId,
        String flowId,
        long reversion
    ) {
        return repository.findById(
            dsl,
            companyId,
            flowId,
            reversion
        ).orElseThrow(() ->
            new WorkflowException(
                "Flow reversion does not exist: "
                    + flowId + ":" + reversion
            )
        );
    }

    public static FlowDraft requireDraft(
        FlowDraftRepository repository,
        DSLContext dsl,
        String companyId,
        String flowId
    ) {
        return repository.lockById(dsl, companyId, flowId)
            .orElseThrow(() ->
                new WorkflowException(
                    "FlowDraft does not exist: " + flowId
                )
            );
    }

    public static ActorRef actor(Session<?> session) {
        User user = session.getUser();
        String actorId = firstText(
            user == null ? null : user.getId(),
            session.getUserId(),
            session.getId()
        );
        if (actorId == null) {
            throw new IllegalArgumentException(
                "Session actor id must not be blank"
            );
        }
        String actorName = firstText(
            user == null ? null : user.getName(),
            session.getUserName()
        );
        return ActorRef.create(actorId, actorName);
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
