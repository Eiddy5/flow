package org.cses.flow.core.domains.tasks;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.Identified;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.plugins.TaskOutputs;

import java.util.*;
import java.util.stream.Stream;

/**
 * Base domain object for a bound Task plugin definition.
 *
 * <p>Jackson and Micronaut may populate fields through the protected no-args
 * constructor. No mutation methods are exposed after binding.</p>
 */
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class Task implements TaskInterface {

    @NotBlank
    String id;

    @NotBlank
    String key;

    String displayName;

    @NotNull
    @Builder.Default
    List<Input<?>> inputs = List.of();


    public final String id() {
        return id;
    }

    public final boolean identifiedBy(String taskId) {
        return Objects.equals(id, taskId);
    }

    public final void reidentify(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("Task id must not be blank");
        }
        id = taskId;
    }

    public final String key() {
        return key;
    }

    public final String displayName() {
        return displayName == null || displayName.isBlank()
                ? key()
                : displayName.trim();
    }

    public final List<Input<?>> inputs() {
        return inputs == null ? List.of() : List.copyOf(inputs);
    }

    /**
     * 按统一 Input 声明从调用方同名值绑定本任务参数。
     * @param values 非 null 的调用方输入，只读；未声明的键不传给本任务
     * @return 已转换并校验的不可变输入
     * @throws IllegalArgumentException 输入缺失或类型、约束不满足时抛出
     */
    public Map<String, Object> bindInputs(Map<String, ?> values) {
        Objects.requireNonNull(values, "Task inputs");
        Map<String, Object> bound = new LinkedHashMap<>();
        for (Input<?> input : inputs()) {
            Object value = input.bind(values);
            if (value != null) bound.put(input.getKey(), value);
        }
        return Collections.unmodifiableMap(bound);
    }

    /**
     * 从具体 Output 类型返回只读标量字段元数据，供现有条件与视图消费。
     * @return 代码定义的基础输出字段；复合对象不进入标量字段列表
     */
    public List<Output> outputs() {
        return outputFields().entrySet().stream()
            .filter(entry -> TaskOutputs.dataType(entry.getValue()) != null)
            .map(entry -> Output.create(entry.getKey(), TaskOutputs.dataType(entry.getValue())))
            .toList();
    }

    /**
     * 读取本任务代码定义的输出类型，供字段列表和传输值校验共用。
     * @return 不可变的字段名称与 Java 类型映射
     */
    protected Map<String, Class<?>> outputFields() {
        return TaskOutputs.fields(getClass());
    }

    /**
     * Returns every directly-contained Task definition owned by this Task.
     *
     * <p>The base Task does not own child definitions. A concrete structural
     * Task can override this method to expose its type-specific containment
     * relation.</p>
     */
    public List<Task> definitionChildren() {
        return List.of();
    }

    /**
     * 判断代码定义的结果类型是否声明指定字段，包括复合字段。
     * @param outputKey 非 null 的业务字段名
     * @return 声明存在时为 true
     */
    public final boolean declaresOutput(String outputKey) {
        return outputFields().containsKey(outputKey);
    }

    public final boolean declaresInput(String inputKey) {
        return inputs().stream().anyMatch(input ->
                input.getKey().equals(inputKey)
        );
    }

    /**
     * 校验传输结果只包含具体 Output 中声明的字段，并规范化已知标量。
     * @param actualOutputs 非 null 的运行输出映射，只读
     * @return 不可变结果映射；复合字段保留原传输值
     * @throws WorkflowException 结果为空引用、字段未声明或标量类型错误时抛出
     */
    public final Map<String, Object> validateOutputs(Map<String, ?> actualOutputs) {
        if (actualOutputs == null) {
            throw new WorkflowException("Task outputs must be provided");
        }
        var fields = outputFields();
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (var entry : actualOutputs.entrySet()) {
            if (!fields.containsKey(entry.getKey())) {
                throw new WorkflowException("Task outputs were not declared: " + entry.getKey());
            }
            var type = TaskOutputs.dataType(fields.get(entry.getKey()));
            try {
                normalized.put(entry.getKey(), type == null || entry.getValue() == null
                    ? entry.getValue() : type.normalize(entry.getValue()));
            } catch (IllegalArgumentException exception) {
                throw new WorkflowException(exception.getMessage());
            }
        }
        return Collections.unmodifiableMap(normalized);
    }

    public final Optional<Task> findDescendant(String taskId) {
        return definitionChildren().stream()
                .flatMap(task -> Stream.concat(
                        Stream.of(task),
                        task.allDescendants().stream()
                ))
                .filter(task -> task.id.equals(taskId))
                .findFirst();
    }

    public final List<Task> allDescendants() {
        return definitionChildren().stream()
                .flatMap(task -> Stream.concat(
                        Stream.of(task),
                        task.allDescendants().stream()
                ))
                .toList();
    }

    /**
     * Concrete subtypes can expose additional equality state when needed.
     */
    protected Object typeSpecificEqualityState() {
        return null;
    }

    @Override
    public final boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (!(value instanceof Task other)) {
            return false;
        }
        return getClass().equals(other.getClass())
                && Objects.equals(id, other.id)
                && Objects.equals(key, other.key)
                && Objects.equals(displayName(), other.displayName())
                && Objects.equals(inputs(), other.inputs())
                && Objects.equals(outputs(), other.outputs())
                && Objects.equals(
                    definitionChildren(),
                    other.definitionChildren()
                )
                && Objects.equals(
                    typeSpecificEqualityState(),
                    other.typeSpecificEqualityState()
                );
    }

    @Override
    public final int hashCode() {
        return Objects.hash(
                getClass(),
                id,
                key,
                displayName(),
                inputs(),
                outputs(),
                definitionChildren(),
                typeSpecificEqualityState()
        );
    }
}
