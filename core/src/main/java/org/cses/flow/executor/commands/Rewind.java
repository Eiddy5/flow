package org.cses.flow.executor.commands;

import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.utils.SessionUtil;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Objects;

/**
 * Requests a new rewind fragment from one paused source to one historical
 * target TaskRun.
 */
public record Rewind(
        String executionId,
        String companyId,
        String actorId,
        String sourceTaskRunId,
        String targetTaskRunId,
        String reason
) implements ExecutionCommand {

    public Rewind {
        executionId = requireText(executionId, "Execution id");
        companyId = requireText(companyId, "Company id");
        actorId = requireText(actorId, "Actor id");
        sourceTaskRunId = requireText(
                sourceTaskRunId,
                "Rewind source TaskRun id"
        );
        targetTaskRunId = requireText(
                targetTaskRunId,
                "Rewind target TaskRun id"
        );
        reason = requireText(reason, "Rewind reason");
    }

    public static Rewind from(
            Session<? extends User> session,
            String executionId,
            String sourceTaskRunId,
            String targetTaskRunId,
            String reason
    ) {
        Objects.requireNonNull(session, "Session must not be null");
        ActorRef actor = SessionUtil.user(session);
        return new Rewind(
                executionId,
                session.getCompanyId(),
                actor.id(),
                sourceTaskRunId,
                targetTaskRunId,
                reason
        );
    }

    @Override
    public Type getType() {
        return Type.REWIND;
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
        requireText(sourceTaskRunId, "Rewind source TaskRun id");
        requireText(targetTaskRunId, "Rewind target TaskRun id");
        requireText(reason, "Rewind reason");
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

    public String getSourceTaskRunId() {
        return sourceTaskRunId;
    }

    public String getTargetTaskRunId() {
        return targetTaskRunId;
    }

    public String getReason() {
        return reason;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
