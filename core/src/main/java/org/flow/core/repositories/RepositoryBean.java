package org.flow.core.repositories;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.inject.Singleton;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Defines a repository as a Micronaut bean.
 * Convenient repository singleton annotation.
 */
@Documented
@Singleton
@Retention(RUNTIME)
@Target({ ElementType.METHOD, ElementType.ANNOTATION_TYPE, ElementType.TYPE, ElementType.FIELD })
public @interface RepositoryBean {
}
