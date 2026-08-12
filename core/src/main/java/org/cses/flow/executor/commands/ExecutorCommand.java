package org.cses.flow.executor.commands;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.cses.flow.queues.event.DispatchEvent;
import org.jooq.DSLContext;

/**
 * Durable instruction accepted by the Executor command Queue.
 *
 * <p>Commands carry the initiating identity because consumption happens
 * outside the caller thread. {@link #dsl()} is runtime-only: ordinary start
 * commands return {@code null}, while a trusted caller can explicitly bind a
 * current transaction when it must atomically materialize and enqueue a
 * pending Execution.</p>
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "type"
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = Create.class, name = "CREATE")
})
public sealed interface ExecutorCommand extends DispatchEvent
    permits Create {

    String QUEUE_NAME = "flow-executor-command";

    Type getType();

    String getExecutionId();

    String getCompanyId();

    String getActorId();

    String getActorName();

    String getSessionId();

    String getIp();

    org.paas.session.Device getDevice();

    String getDeviceId();

    String getAppVersion();

    String getOsVersion();

    void validate();

    @Override
    default String key() {
        return getExecutionId();
    }

    @Override
    @JsonIgnore
    DSLContext dsl();

    enum Type {
        CREATE
    }
}
