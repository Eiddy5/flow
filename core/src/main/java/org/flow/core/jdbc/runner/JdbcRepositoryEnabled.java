package org.flow.core.jdbc.runner;

import java.lang.annotation.*;

import io.micronaut.context.annotation.DefaultImplementation;
import io.micronaut.context.annotation.Requires;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.PACKAGE, ElementType.TYPE })
@Requires(property = "flow.repository.type", value = "postgres")
@DefaultImplementation
public @interface JdbcRepositoryEnabled {
}