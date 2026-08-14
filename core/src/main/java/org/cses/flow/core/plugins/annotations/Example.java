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
     * Whether each source block is already a complete Flow definition.
     *
     * <p>Complete examples contain the Flow {@code key} and {@code tasks}
     * fields and can be deployed without adding plugin fields. Flow and Task
     * system identity fields are never added to or accepted from examples.
     * {@code false} is retained for legacy metadata; new plugin examples
     * should use complete Flow YAML.</p>
     */
    boolean full() default false;
}
