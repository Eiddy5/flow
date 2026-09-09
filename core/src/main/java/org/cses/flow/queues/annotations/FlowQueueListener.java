package org.cses.flow.queues.annotations;

import io.micronaut.context.annotation.Executable;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registers a public, nonstatic, void method on a singleton bean as a Shared consumer.
 * Its sole parameter must declare {@link FlowQueue}; normal completion accepts the
 * message and an exception requests PAAS redelivery. The method must finish its work
 * before returning and be safe for concurrent calls when concurrency exceeds one.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Executable(processOnStartup = true)
public @interface FlowQueueListener {

    /**
     * Selects the competing consumer group for this method.
     * @return nonblank durable subscription name shared by equivalent application instances
     */
    String subscription();

    /**
     * Sets the number of consumers invoking this singleton method.
     * @return positive number of serial PAAS consumers in this application instance
     */
    int concurrency() default 1;
}
