package org.cses.flow.executor;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.cses.flow.executor.commands.ExecutorCommand;
import org.cses.flow.executor.handlers.ExecutorCommandHandler;
import org.cses.flow.infrastructure.jooq.FlowJooqCondition;
import org.cses.flow.queues.DispatchQueue;
import org.cses.flow.queues.QueueSubscription;

import java.util.Objects;

/**
 * Default Executor lifecycle that only routes Queue commands to their
 * handler.
 */
@Context
@Bean(preDestroy = "close")
@Requires(condition = FlowJooqCondition.class)
public final class DefaultExecutor implements AutoCloseable {

    private final QueueSubscription commandSubscription;

    @Inject
    public DefaultExecutor(
        @Named(ExecutorCommand.QUEUE_NAME)
        DispatchQueue<ExecutorCommand> commandQueue,
        ExecutorCommandHandler commandHandler
    ) {
        Objects.requireNonNull(commandHandler, "commandHandler");
        commandSubscription = Objects.requireNonNull(
            commandQueue,
            "commandQueue"
        ).subscribe(commandHandler::handle);
    }

    @Override
    public void close() {
        commandSubscription.close();
    }
}
