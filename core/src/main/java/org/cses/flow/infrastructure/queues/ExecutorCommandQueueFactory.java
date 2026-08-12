package org.cses.flow.infrastructure.queues;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.cses.flow.executor.commands.ExecutorCommand;
import org.cses.flow.infrastructure.jooq.FlowDatabase;
import org.cses.flow.infrastructure.jooq.FlowJooqCondition;
import org.cses.flow.queues.DispatchQueue;
import org.x9.jooq.JOOQ;

/**
 * Composition root for the durable Executor command Queue.
 */
@Factory
@Requires(condition = FlowJooqCondition.class)
public final class ExecutorCommandQueueFactory {

    @Singleton
    @Named(ExecutorCommand.QUEUE_NAME)
    @Bean(preDestroy = "close")
    DispatchQueue<ExecutorCommand> executorCommandQueue(
        @Named(FlowDatabase.DATA_SOURCE_NAME) JOOQ jooq
    ) {
        return new DefaultDispatchQueue<>(
            ExecutorCommand.QUEUE_NAME,
            jooq,
            ExecutorCommand.class
        );
    }
}
