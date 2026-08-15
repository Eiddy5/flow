package org.cses.flow.executor;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.cses.flow.executor.commands.ExecutionCommand;
import org.cses.flow.executor.handlers.ExecutionCommandEventHandler;
import org.cses.flow.infrastructure.jooq.FlowJooqCondition;
import org.cses.flow.queues.DispatchQueue;
import org.cses.flow.queues.QueueSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default Executor lifecycle that routes external commands and internal
 * state hand-offs to their respective handlers.
 */
@Context
@Bean(preDestroy = "close")
@Requires(condition = FlowJooqCondition.class)
public final class DefaultExecutor implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DefaultExecutor.class);

    private final QueueSubscription commandSubscription;
    private final QueueSubscription eventSubscription;

    @Inject
    public DefaultExecutor(
            @Named(ExecutionCommand.QUEUE_NAME)
            DispatchQueue<ExecutionCommand> commandQueue,
            ExecutionCommandEventHandler commandHandler,
            @Named(ExecutorEvent.QUEUE_NAME)
            DispatchQueue<ExecutorEvent> eventQueue,
            org.cses.flow.executor.handlers.ExecutorEventHandler eventHandler
    ) {
        Objects.requireNonNull(commandHandler, "commandHandler");
        commandSubscription = Objects.requireNonNull(
            commandQueue,
            "commandQueue"
        ).subscribe(commandHandler::handle);
        eventSubscription = Objects.requireNonNull(
            eventQueue,
            "eventQueue"
        ).subscribe(eventHandler::handle);
    }

    @Override
    public void close() {
        try {
            eventSubscription.close();
        } finally {
            commandSubscription.close();
        }
    }
}
