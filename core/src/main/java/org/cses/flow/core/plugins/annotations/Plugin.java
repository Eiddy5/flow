package org.cses.flow.core.plugins.annotations;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.DefaultScope;
import io.micronaut.core.annotation.Introspected;
import jakarta.inject.Singleton;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a build-time classpath plugin for Micronaut discovery.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Bean(typed = org.cses.flow.core.plugins.Plugin.class)
@DefaultScope(Singleton.class)
@Introspected(
    accessKind = Introspected.AccessKind.FIELD,
    visibility = Introspected.Visibility.ANY
)
public @interface Plugin {

    /**
     * Optional human-readable name used by plugin catalogs.
     */
    String title() default "";

    /**
     * Optional human-readable description used by plugin catalogs.
     */
    String description() default "";
}
