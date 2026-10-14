package org.flow.core.junit.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

import org.flow.core.junit.extensions.FlowExecutorExtension;
import org.flow.core.models.executions.ExecutionKind;

import static org.flow.core.tenant.TenantService.MAIN_TENANT;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(FlowExecutorExtension.class)
public @interface ExecuteFlow {

    String value();

    String timeout() default "PT60S";

    String tenantId() default MAIN_TENANT;

    ExecutionKind executionKind() default ExecutionKind.NORMAL;
}
