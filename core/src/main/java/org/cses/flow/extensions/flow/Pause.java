package org.cses.flow.extensions.flow;

import com.fasterxml.jackson.annotation.JsonSetter;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.OrchestrationTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.validations.ModelInvariant;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * External-resume orchestration gate with one mandatory pre-pause Task.
 */
@Plugin(
    title = "暂停",
    description = "执行暂停前任务，等待外部回调后继续流程"
)
@SuperBuilder
@NoArgsConstructor
public final class Pause extends Task implements OrchestrationTask, ModelInvariant {

    private static final DatatypeFactory DURATION_FACTORY = durationFactory();

    @NotNull
    @Schema(
        title = "暂停任务",
        description = "进入 PAUSED 前必须完整执行的 Task"
    )
    private Task pause;

    @NotNull
    @Builder.Default
    @Schema(
        title = "恢复输入",
        description = "外部 Resume 回调接受的 Input 定义；允许为空"
    )
    private List<Input<?>> resume = List.of();

    @Schema(
        title = "等待时长",
        description = "正 ISO 8601 表达式；与 behavior 同时配置",
        example = "PT5M"
    )
    private String duration;

    @Schema(
        title = "超时行为",
        description = "duration 到期后的目标行为；与 duration 同时配置"
    )
    private Behavior behavior;

    public Task pause() {
        return pause;
    }

    public List<Input<?>> resume() {
        return resume == null ? List.of() : List.copyOf(resume);
    }

    public Optional<String> duration() {
        return Optional.ofNullable(duration);
    }

    public Optional<Behavior> behavior() {
        return Optional.ofNullable(behavior);
    }

    @Override
    public List<Output> outputs() {
        return resume().stream()
            .map(input -> Output.create(input.getKey(), input.getType()))
            .toList();
    }

    @Override
    public List<Task> definitionChildren() {
        return pause == null ? List.of() : List.of(pause);
    }

    @Override
    public boolean pausesTaskRun() {
        return true;
    }

    /**
     * Validates and normalizes external Resume data using the configured
     * concrete Input definitions.
     */
    public Map<String, Object> validateResume(
        Map<String, ?> actualInputs
    ) {
        if (actualInputs == null) {
            throw new WorkflowException("Pause resume inputs must be provided");
        }

        LinkedHashSet<String> declared = resume().stream()
            .map(Input::getKey)
            .collect(java.util.stream.Collectors.toCollection(
                LinkedHashSet::new
            ));
        LinkedHashSet<String> unsupported = new LinkedHashSet<>(
            actualInputs.keySet()
        );
        unsupported.removeAll(declared);
        if (!unsupported.isEmpty()) {
            throw new WorkflowException(
                "Pause resume inputs were not declared: " + unsupported
            );
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Input<?> input : resume()) {
            String key = input.getKey();
            boolean supplied = actualInputs.containsKey(key);
            Object value = supplied
                ? actualInputs.get(key)
                : input.getDefaultValue();
            try {
                Object accepted = input.normalized(value);
                if (supplied || accepted != null) {
                    normalized.put(key, accepted);
                }
            } catch (IllegalArgumentException exception) {
                throw new WorkflowException(exception.getMessage(), exception);
            }
        }
        return Collections.unmodifiableMap(normalized);
    }

    @JsonSetter("duration")
    @ReflectiveAccess
    private void bindDuration(String source) {
        duration = normalizeDuration(source);
    }

    @Override
    public void verifyModelInvariant() {
        if (!tasks().isEmpty()) {
            throw new IllegalArgumentException(
                "Pause tasks must be empty; use the pause property"
            );
        }
        if (pause == null) {
            throw new IllegalArgumentException(
                "Pause requires exactly one pause Task"
            );
        }
        if (resume == null) {
            throw new IllegalArgumentException(
                "Pause resume inputs must not be null"
            );
        }

        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (Input<?> input : resume) {
            if (input == null) {
                throw new IllegalArgumentException(
                    "Pause resume inputs must not contain null values"
                );
            }
            input.validateDefinition();
            if (!keys.add(input.getKey())) {
                throw new IllegalArgumentException(
                    "Pause resume inputs contains duplicate key: "
                        + input.getKey()
                );
            }
        }

        List<Output> derivedOutputs = outputs();
        if (!configuredOutputs().isEmpty()
            && !configuredOutputs().equals(derivedOutputs)) {
            throw new IllegalArgumentException(
                "Pause outputs must match the resume Input definitions"
            );
        }

        if ((duration == null) != (behavior == null)) {
            throw new IllegalArgumentException(
                "Pause duration and behavior must both be present or absent"
            );
        }
        if (duration != null) {
            duration = normalizeDuration(duration);
        }
    }

    static String normalizeDuration(String source) {
        if (source == null) {
            return null;
        }
        if (source.isBlank()) {
            throw new IllegalArgumentException(
                "Pause duration must not be blank"
            );
        }
        String normalized = source.trim().toUpperCase(Locale.ROOT);
        try {
            javax.xml.datatype.Duration parsed =
                DURATION_FACTORY.newDuration(normalized);
            if (parsed.getSign() <= 0) {
                throw new IllegalArgumentException(
                    "Pause duration must be positive: " + source
                );
            }
            return parsed.toString().toUpperCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null
                && exception.getMessage().startsWith(
                    "Pause duration must be positive"
                )) {
                throw exception;
            }
            throw new IllegalArgumentException(
                "Pause duration must be a valid ISO 8601 expression: "
                    + source,
                exception
            );
        }
    }

    @Override
    protected Object typeSpecificEqualityState() {
        return java.util.Arrays.asList(
            pause,
            resume(),
            Optional.ofNullable(duration),
            Optional.ofNullable(behavior)
        );
    }

    private static DatatypeFactory durationFactory() {
        try {
            return DatatypeFactory.newInstance();
        } catch (DatatypeConfigurationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    public enum Behavior {
        RESUME(State.Type.RUNNING),
        WARN(State.Type.WARNING),
        CANCEL(State.Type.KILLED),
        FAIL(State.Type.FAILED);

        private final State.Type state;

        Behavior(State.Type state) {
            this.state = state;
        }

        public State.Type state() {
            return state;
        }
    }
}
