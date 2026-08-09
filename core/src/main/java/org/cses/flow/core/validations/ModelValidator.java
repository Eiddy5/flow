package org.cses.flow.core.validations;

import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import io.micronaut.validation.validator.Validator;
import org.cses.flow.core.domains.flows.Data;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.tasks.OrchestrationTask;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Active validation entry point for bound or directly-created models.
 */
@Singleton
public final class ModelValidator {

    private final Validator validator;

    public ModelValidator(Validator validator) {
        this.validator = validator;
    }

    public <T> T validate(T model) {
        Objects.requireNonNull(model, "Model");
        validateConstraints(model);
        if (model instanceof Task task) {
            validateTask(task);
        }
        return model;
    }

    private <T> void validateConstraints(T model) {
        Set<ConstraintViolation<T>> violations = validator.validate(model);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    private void validateTask(Task task) {
        if (task instanceof ModelInvariant invariant) {
            invariant.verifyModelInvariant();
        }
        requireTrimmedText(task.id(), "Task id");
        requireTrimmedText(task.key(), "Task key");
        Objects.requireNonNull(task.route(), "Task route");
        boolean runnable = task instanceof RunnableTask;
        boolean orchestration = task instanceof OrchestrationTask;
        if (runnable == orchestration) {
            throw new IllegalArgumentException(
                "Task must implement exactly one runtime capability: "
                    + task.getType()
            );
        }
        requireUniqueData(task.inputs(), "Task inputs");
        requireUniqueData(task.outputs(), "Task outputs");
        requireUniqueText(task.dependOn(), "Task dependOn");
        for (Input<?> input : task.inputs()) {
            input.validateDefinition();
        }
        for (Task child : task.definitionChildren()) {
            validateConstraints(child);
            validateTask(child);
        }
    }

    private static void requireTrimmedText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException(
                field + " must not have surrounding whitespace"
            );
        }
    }

    private static void requireUniqueData(
        List<? extends Data> values,
        String field
    ) {
        Set<String> keys = new HashSet<>();
        for (Data value : values) {
            if (value == null) {
                throw new IllegalArgumentException(
                    field + " must not contain null values"
                );
            }
            if (!keys.add(value.getKey())) {
                throw new IllegalArgumentException(
                    field + " contains duplicate key: " + value.getKey()
                );
            }
        }
    }

    private static void requireUniqueText(
        List<String> values,
        String field
    ) {
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                    field + " values must not be blank"
                );
            }
            if (!unique.add(value)) {
                throw new IllegalArgumentException(
                    field + " contains duplicate key: " + value
                );
            }
        }
    }
}
