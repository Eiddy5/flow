package org.cses.flow.executor.commands;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.cses.flow.core.domains.ActorRef;
import org.jooq.DSLContext;
import org.paas.json.SerializableObject;
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
public final class Resume extends SerializableObject
    implements ExecutionCommand {

    private String executionId;
    private String companyId;
    private String actorId;
    private String taskRunId;
    private Map<String, Object> outputs = Map.of();

    @JsonIgnore
    private transient DSLContext dsl;

    public Resume() {
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
        ActorRef actor = ActorRef.from(session);

        Resume command = new Resume();
        command.executionId = executionId;
        command.companyId = session.getCompanyId();
        command.actorId = actor.id();
        command.taskRunId = taskRunId;
        command.outputs = immutableOutputs(outputs);
        command.validate();
        return command;
    }

    @Override
    public Type getType() {
        return Type.RESUME;
    }

    @Override
    public void validate() {
        executionId = requireText(executionId, "Execution id");
        companyId = requireText(companyId, "Company id");
        actorId = requireText(actorId, "Actor id");
        taskRunId = requireText(taskRunId, "TaskRun id");
        outputs = immutableOutputs(outputs);
    }

    @Override
    @JsonIgnore
    public DSLContext dsl() {
        return dsl;
    }

    @Override
    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    @Override
    public String getCompanyId() {
        return companyId;
    }

    public void setCompanyId(String companyId) {
        this.companyId = companyId;
    }

    @Override
    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
    }

    public String getTaskRunId() {
        return taskRunId;
    }

    public void setTaskRunId(String taskRunId) {
        this.taskRunId = taskRunId;
    }

    public Map<String, Object> getOutputs() {
        return outputs == null ? Map.of() : Map.copyOf(outputs);
    }

    public void setOutputs(Map<String, Object> outputs) {
        this.outputs = immutableOutputs(outputs);
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
