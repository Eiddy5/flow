package org.cses.flow.executor;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.cses.flow.executor.commands.ExecutionCommand;
import org.cses.flow.executor.handlers.ExecutionCommandEventHandler;
import org.cses.flow.executor.handlers.ExecutorEventMessageHandler;
import org.cses.flow.infrastructure.jooq.FlowJooqCondition;
import org.cses.flow.queues.annotations.FlowQueueListener;

import java.util.Objects;

/** Routes Pulsar deliveries to the existing command and scheduling handlers. */
@Singleton
@Requires(condition = FlowJooqCondition.class)
public class DefaultExecutor {
    private ExecutionCommandEventHandler commandHandler;
    private ExecutorEventMessageHandler eventHandler;

    /**
     * Retains the handlers without creating independent transport subscriptions.
     * @param commandHandler handler restoring and applying external command facts
     * @param eventHandler handler running one internal scheduling cycle
     */
    public DefaultExecutor(ExecutionCommandEventHandler commandHandler, ExecutorEventMessageHandler eventHandler) {
        this.commandHandler = Objects.requireNonNull(commandHandler, "commandHandler");
        this.eventHandler = Objects.requireNonNull(eventHandler, "eventHandler");
    }

    /**
     * Completes command persistence and internal publication before allowing ACK.
     * @param command decoded execution command
     * @throws RuntimeException when handling fails; PAAS requests redelivery
     */
    @FlowQueueListener(subscription = ExecutionCommand.QUEUE_NAME)
    public void onCommand(ExecutionCommand command) {
        commandHandler.handle(command);
    }

    /**
     * Completes one scheduling cycle before allowing ACK.
     * @param event decoded internal scheduling signal
     * @throws RuntimeException when handling fails; PAAS requests redelivery
     */
    @FlowQueueListener(subscription = ExecutorEvent.QUEUE_NAME)
    public void onEvent(ExecutorEvent event) {
        eventHandler.handle(event);
    }
}
