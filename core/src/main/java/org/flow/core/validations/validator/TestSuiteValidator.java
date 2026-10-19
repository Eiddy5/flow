package org.flow.core.validations.validator;

import java.util.ArrayList;
import java.util.List;

import org.flow.core.repositories.FlowRepositoryInterface;
import org.flow.core.test.TestSuite;
import org.flow.core.validations.TestSuiteValidation;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.validation.validator.constraints.ConstraintValidator;
import io.micronaut.validation.validator.constraints.ConstraintValidatorContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
public class TestSuiteValidator implements ConstraintValidator<TestSuiteValidation, TestSuite> {
    @Inject
    private FlowRepositoryInterface flowRepository;

    /**
     * 检查测试套件引用的流程是否存在于套件所属租户，不改变测试套件。
     * @param value 待校验套件，null 表示跳过校验
     * @param annotationMetadata 约束注解元数据，不能为 null
     * @param context 校验上下文，用于登记引用不存在的错误
     * @return 流程存在或套件为 null 时返回 true
     */
    @Override
    public boolean isValid(
        @Nullable TestSuite value,
        @NonNull AnnotationValue<TestSuiteValidation> annotationMetadata,
        @NonNull ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        List<String> violations = new ArrayList<>();

        if (flowRepository.findById(value.getTenantId(), value.getNamespace(), value.getFlowId()).isEmpty()) {
            violations.add("Flow with id: '%s' does not exist on Namespace: '%s'.".formatted(value.getFlowId(), value.getNamespace()));
        }

        if (!violations.isEmpty()) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("Invalid TestSuite: " + String.join(", ", violations))
                .addConstraintViolation();
            return false;
        } else {
            return true;
        }
    }
}
