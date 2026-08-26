package org.cses.flow.executor.commands;

import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.utils.SessionUtil;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Requests one durable resume of one exact paused TaskRun.
 *
 * <p>The consumer reloads the Execution and its bound Flow reversion. The
 * command therefore carries only the tenant, actor, target identities and
 * normalized resume data; it does not duplicate Flow or Session snapshots.</p>
 */
public record Resume(
    String executionId,
    String companyId,
    String actorId,
    String taskRunId,
    Map<String, Object> outputs
) implements ExecutionCommand {

    public Resume {
        executionId = requireText(executionId, "Execution id");
        companyId = requireText(companyId, "Company id");
        actorId = requireText(actorId, "Actor id");
        taskRunId = requireText(taskRunId, "TaskRun id");
        outputs = immutableOutputs(outputs);
    }

    public static Resume from(
        Session<? extends User> session,
        String executionId,
        String taskRunId,
        Map<String, ?> outputs
    ) {
        Objects.requireNonNull(session, "Session must not be null");
        if (outputs == null) {
            throw new IllegalArgumentException(
                "Resume outputs must not be null"
            );
        }
        ActorRef actor = SessionUtil.user(session);

        return from(
            executionId,
            session.getCompanyId(),
            actor.id(),
            taskRunId,
            immutableOutputs(outputs)
        );
    }

    public static Resume from(
        String executionId,
        String companyId,
        String actorId,
        String taskRunId,
        Map<String, ?> outputs
    ) {
        return new Resume(
            executionId,
            companyId,
            actorId,
            taskRunId,
            immutableOutputs(outputs)
        );
    }

    @Override
    public Type getType() {
        return Type.RESUME;
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
        requireText(taskRunId, "TaskRun id");
        immutableOutputs(outputs);
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

    public String getTaskRunId() {
        return taskRunId;
    }

    public Map<String, Object> getOutputs() {
        return outputs;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static Map<String, Object> immutableOutputs(
        Map<String, ?> values
    ) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copied = new LinkedHashMap<>();
        values.forEach((key, value) -> copied.put(
            requireText(key, "Resume output key"),
            Objects.requireNonNull(value, "Resume output value")
        ));
        return Map.copyOf(copied);
    }
}
