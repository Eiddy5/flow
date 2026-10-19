package org.flow.queue.jdbc;

import java.lang.annotation.*;

import io.micronaut.context.annotation.Requires;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.PACKAGE, ElementType.TYPE })
@Requires(property = "flow.queue.type", value = "postgres")
public @interface JdbcQueueEnabled {
}