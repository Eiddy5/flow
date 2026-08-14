package org.cses.flow.executor.commands;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.cses.flow.core.domains.ActorRef;
import org.jooq.DSLContext;
import org.paas.json.SerializableObject;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Objects;

/**
 * Requests cancellation of one Execution through the Executor command Queue.
 */
public final class Cancel extends SerializableObject
    implements ExecutionCommand {

    private String executionId;
    private String companyId;
    private String actorId;

    @JsonIgnore
    private transient DSLContext dsl;

    public Cancel() {
    }

    public static Cancel from(
        Session<? extends User> session,
        String executionId
    ) {
        Objects.requireNonNull(session, "Session must not be null");
        ActorRef actor = ActorRef.from(session);

        Cancel command = new Cancel();
        command.executionId = executionId;
        command.companyId = session.getCompanyId();
        command.actorId = actor.id();
        command.validate();
        return command;
    }

    @Override
    public Type getType() {
        return Type.CANCEL;
    }

    @Override
    public void validate() {
        executionId = requireText(executionId, "Execution id");
        companyId = requireText(companyId, "Company id");
        actorId = requireText(actorId, "Actor id");
    }

    public Cancel inTransaction(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        return this;
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

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
