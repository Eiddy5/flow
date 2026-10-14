package org.flow.core.junit.annotations;

import java.lang.annotation.*;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

import org.flow.core.junit.extensions.FlowTestExtension;

import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.annotation.Executable;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.test.annotation.TransactionMode;
import io.micronaut.test.condition.TestActiveCondition;

@Tag("integration")
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD, ElementType.ANNOTATION_TYPE, ElementType.TYPE })
@ExtendWith(FlowTestExtension.class)
@Factory
@Inherited
@Requires(condition = TestActiveCondition.class)
@Executable
public @interface FlowTest {

    boolean startRunner() default false;

    boolean startScheduler() default false;

    boolean startWorker() default true;


    /**
     * Whether the embedded {@code SystemWorker} should be started by
     * {@link org.flow.core.runners.TestRunner}. Defaults to {@code false}
     * so test runs that don't exercise SystemTasks don't pay for the
     * SystemWorker's thread pool and queue subscriptions. Tests that exercise
     * a SystemTask must opt in.
     */
    boolean startSystemWorker() default false;

    Class<?> application() default void.class;

    String[] environments() default {};

    String[] packages() default {};

    String[] propertySources() default {};

    boolean rollback() default true;

    boolean transactional() default false;

    boolean rebuildContext() default false;

    Class<? extends ApplicationContextBuilder>[] contextBuilder() default {};

    TransactionMode transactionMode() default TransactionMode.SEPARATE_TRANSACTIONS;

    boolean startApplication() default true;

    boolean resolveParameters() default true;
}
