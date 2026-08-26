package org.cses.flow.executor.commands;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.cses.flow.queues.event.DispatchEvent;

/**
 * Durable instruction accepted by the Executor command Queue.
 *
 * <p>Commands are durable data payloads and do not carry a caller
 * transaction. The command Queue therefore uses its own transaction for
 * ordinary command publishing. Command-specific identity is kept on each
 * concrete command: Create materializes its preassigned stable Execution id,
 * while Resume and Cancel target an already materialized Execution.</p>
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = Create.class, name = "CREATE"),
        @JsonSubTypes.Type(value = Resume.class, name = "RESUME"),
        @JsonSubTypes.Type(value = Cancel.class, name = "CANCEL")
})
public sealed interface ExecutionCommand extends DispatchEvent permits Create, Resume, Cancel {

    String QUEUE_NAME = "flow-executor-command";

    Type getType();

    void validate();

    enum Type {
        CREATE,
        RESUME,
        CANCEL
    }
}
