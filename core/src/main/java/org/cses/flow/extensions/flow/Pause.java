package org.cses.flow.extensions.flow;

import com.fasterxml.jackson.annotation.JsonSetter;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.OrchestrationTask;
import org.cses.flow.core.domains.tasks.Output;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.plugins.annotations.Example;
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
    description = "执行前置任务后暂停，接收恢复输入后继续",
    examples = {
        @Example(
            title = "等待外部审批",
            code = """
                key: pause-flow
                tasks:
                  - key: wait-approval
                    type: org.cses.flow.extensions.flow.Pause
                    onPause:
                      key: create-approval
                      type: org.cses.flow.extensions.log.Log
                      message: "创建暂停前记录"
                    onResume:
                      - key: decision
                        type: STRING
                        displayName: 审批结果
                        required: true
                    duration: PT24H
                    behavior: FAIL
                """,
            full = true
        )
    }
)
@SuperBuilder
@NoArgsConstructor
public class Pause extends Task implements OrchestrationTask<Pause.Output>, ModelInvariant {

    private static final DatatypeFactory DURATION_FACTORY = durationFactory();

    @NotNull
    @Schema(
        title = "暂停任务",
        description = "进入 PAUSED 前必须完整执行的 Task"
    )
    private Task onPause;

    @NotNull
    @Builder.Default
    @Schema(
        title = "恢复输入",
        description = "外部 Resume 回调接受的 Input 定义；允许为空"
    )
    private List<Input<?>> onResume = List.of();

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

    /**
     * 返回进入暂停前执行的任务。
     * @return 定义中持有的任务；未完成模型校验时可能为 null
     */
    public Task onPause() {
        return onPause;
    }

    /**
     * 返回恢复输入定义的不可变列表，不复制列表中的 Input 对象。
     * @return 恢复输入定义；未配置时返回空列表
     */
    public List<Input<?>> onResume() {
        return onResume == null ? List.of() : List.copyOf(onResume);
    }

    public Optional<String> duration() {
        return Optional.ofNullable(duration);
    }

    public Optional<Behavior> behavior() {
        return Optional.ofNullable(behavior);
    }

    /**
     * 返回包含暂停前任务的不可变列表，供定义树遍历使用。
     * @return 包含 onPause 原对象的列表；未配置时返回空列表
     */
    @Override
    public List<Task> definitionChildren() {
        return onPause == null ? List.of() : List.of(onPause);
    }

    /**
     * 按 onResume 声明收集每个 Input 自行绑定的回调值，独立于 Task.outputs。
     * @param actualInputs 非 null 的只读提交映射，允许显式 null 值
     * @return 不可变结果映射，保留显式提交的 null，省略缺失且未绑定到值的字段
     * @throws WorkflowException 当提交映射为空引用、存在未声明字段或 Input 绑定失败时抛出
     */
    public Map<String, Object> bindResume(
        Map<String, ?> actualInputs
    ) {
        if (actualInputs == null) {
            throw new WorkflowException("Pause onResume inputs must be provided");
        }

        LinkedHashSet<String> declared = onResume().stream()
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
                "Pause onResume inputs were not declared: " + unsupported
            );
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Input<?> input : onResume()) {
            String key = input.getKey();
            try {
                Object accepted = input.bind(actualInputs);
                if (actualInputs.containsKey(key) || accepted != null) {
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

    /**
     * 检查 Pause 的任务、onResume 字段集合和超时配置，不重复检查已合法创建的 Input。
     * @throws IllegalArgumentException 当必需任务缺失、onResume 集合非法或超时配置非法时抛出
     */
    @Override
    public void verifyModelInvariant() {
        if (onPause == null) {
            throw new IllegalArgumentException(
                "Pause requires exactly one onPause Task"
            );
        }
        if (onResume == null) {
            throw new IllegalArgumentException(
                "Pause onResume inputs must not be null"
            );
        }

        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (Input<?> input : onResume) {
            if (input == null) {
                throw new IllegalArgumentException(
                    "Pause onResume inputs must not contain null values"
                );
            }
            if (!keys.add(input.getKey())) {
                throw new IllegalArgumentException(
                    "Pause onResume inputs contains duplicate key: "
                        + input.getKey()
                );
            }
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

    /**
     * 提供本类型参与相等比较的字段快照。
     * @return 包含暂停任务、恢复输入及超时配置的列表，任务和输入对象沿用原引用
     */
    @Override
    protected Object typeSpecificEqualityState() {
        return java.util.Arrays.asList(
            onPause,
            onResume(),
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

    public static class Output implements org.cses.flow.core.domains.tasks.Output{

    }
}
