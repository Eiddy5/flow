package org.cses.flow.executor.commands;

import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.utils.SessionUtil;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Objects;

/**
 * Requests cancellation of one Execution through the Executor command Queue.
 */
public record Cancel(
        String executionId,
        String companyId,
        String actorId
) implements ExecutionCommand {

    public Cancel {
        executionId = requireText(executionId, "Execution id");
        companyId = requireText(companyId, "Company id");
        actorId = requireText(actorId, "Actor id");
    }

    public static Cancel from(
            Session<? extends User> session,
            String executionId
    ) {
        Objects.requireNonNull(session, "Session must not be null");
        ActorRef actor = SessionUtil.user(session);

        return from(
                executionId,
                session.getCompanyId(),
                actor.id()
        );
    }

    public static Cancel from(
            String executionId,
            String companyId,
            String actorId
    ) {
        return new Cancel(executionId, companyId, actorId);
    }

    @Override
    public Type getType() {
        return Type.CANCEL;
    }

    @Override
    public String key() {
        return executionId;
    }

    @Override
    public void validate() {
        requireText(executionId, "Execution id");
        requireText(companyId, "Company id");
        requireText(actorId, "Actor id");
    }

    public String getExecutionId() {
        return executionId;
    }

    public String getCompanyId() {
        return companyId;
    }

    public String getActorId() {
        return actorId;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
