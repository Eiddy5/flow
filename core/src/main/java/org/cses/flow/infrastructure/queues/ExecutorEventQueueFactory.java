package org.cses.flow.infrastructure.queues;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.cses.flow.executor.ExecutorEvent;
import org.cses.flow.infrastructure.jooq.FlowDatabase;
import org.cses.flow.infrastructure.jooq.FlowJooqCondition;
import org.cses.flow.queues.DispatchQueue;
import org.x9.jooq.JOOQ;

/**
 * Composition root for the durable Executor internal-event Queue.
 */
@Factory
@Requires(condition = FlowJooqCondition.class)
public final class ExecutorEventQueueFactory {

    @Singleton
    @Named(ExecutorEvent.QUEUE_NAME)
    @Bean(preDestroy = "close")
    DispatchQueue<ExecutorEvent> executorEventQueue(
        @Named(FlowDatabase.DATA_SOURCE_NAME) JOOQ jooq
    ) {
        return new DefaultDispatchQueue<>(
            ExecutorEvent.QUEUE_NAME,
            jooq,
            ExecutorEvent.class
        );
    }
}
