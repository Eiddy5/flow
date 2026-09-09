package org.cses.flow.queues.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares the logical name and PAAS Pulsar topic of one concrete message type. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface FlowQueue {

    /**
     * Names the queue inside the application.
     * @return nonblank logical queue name, unique within the application
     */
    String name();

    /**
     * Selects the PAAS message destination.
     * @return nonblank topic passed unchanged to PAAS, which owns environment prefixes
     */
    String topic();
}
