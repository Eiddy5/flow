package org.flow.plugin.api.annotations;

import java.lang.annotation.*;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Documented
@Inherited
@Retention(RUNTIME)
@Target({ ElementType.TYPE, ElementType.ANNOTATION_TYPE })
public @interface Examples {
    Example[] value();
}
