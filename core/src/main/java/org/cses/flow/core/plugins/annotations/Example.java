package org.cses.flow.core.plugins.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares one or more source examples embedded in plugin metadata.
 *
 * <p>This annotation is only a value of {@link Plugin#examples()} and cannot
 * be applied directly to a Java declaration.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface Example {

    /**
     * Optional short description of the example.
     */
    String title() default "";

    /**
     * One or more independent source blocks for the example.
     */
    String[] code() default {};

    /**
     * Source language used for syntax highlighting and interpretation.
     */
    String lang() default "yaml";

    /**
     * Whether each source block is already a complete Task definition.
     *
     * <p>Consumers augment partial examples with a unique {@code key} and the
     * declaring plugin's canonical {@code type}. Complete examples already
     * contain both fields. Flow system identity fields are never added to or
     * accepted from examples.</p>
     */
    boolean full() default false;
}
