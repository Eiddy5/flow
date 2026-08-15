package org.cses.flow.core.handlers.flows;

import org.cses.flow.core.domains.ActorRef;
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
        String flowKey
    ) {
        return repository.findLatestByKey(dsl, companyId, flowKey)
            .orElseThrow(() ->
                new WorkflowException("Flow does not exist: " + flowKey)
            );
    }

    public static Flow requireFlow(
        FlowRepository repository,
        DSLContext dsl,
        String companyId,
        String flowKey,
        long flowVersion
    ) {
        return repository.findByKey(
            dsl,
            companyId,
            flowKey,
            flowVersion
        ).orElseThrow(() ->
            new WorkflowException(
                "Flow version does not exist: "
                    + flowKey + ":" + flowVersion
            )
        );
    }

    /**
     * Loads a deployed revision by its technical row id. This is reserved
     * for restoring an Execution's already-bound revision; business callers
     * must use {@link #requireFlow(FlowRepository, DSLContext, String,
     * String, long)} with company, key, and version.
     */
    public static Flow requireFlowById(
        FlowRepository repository,
        DSLContext dsl,
        String companyId,
        String flowId,
        long flowVersion
    ) {
        return repository.findById(
            dsl,
            companyId,
            flowId,
            flowVersion
        ).orElseThrow(() ->
            new WorkflowException(
                "Flow version does not exist: "
                    + flowId + ":" + flowVersion
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
        return ActorRef.from(session);
    }
}
