package org.cses.flow.core.plugins;

import com.fasterxml.classmate.MemberResolver;
import com.fasterxml.classmate.TypeResolver;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.reflect.ReflectionUtils;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.tasks.OrchestrationTask;
import org.cses.flow.core.domains.tasks.ExecutableTask;
import org.cses.flow.core.domains.tasks.Output;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.domains.tasks.VoidOutput;
import org.paas.json.JsonObject;

import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

/** 从 Task 的具体 Output 泛型读取字段，供输出引用和结果边界检查复用。 */
public class TaskOutputs {
    private static ClassValue<Map<String, Class<?>>> fields = new ClassValue<>() {
        /**
         * 解析一次 Task 类型的输出字段，包括父类中绑定的泛型字段。
         * @param type 非 null 的具体 Task 类型
         * @return 不可变的字段名称与 Java 类型映射
         * @throws IllegalArgumentException Task 未声明具体 Output 类型时抛出
         */
        @Override
        protected Map<String, Class<?>> computeValue(Class<?> type) {
            TypeResolver resolver = new TypeResolver();
            var resolved = resolver.resolve(type);
            var capability = resolved.findSupertype(RunnableTask.class);
            if (capability == null) {
                capability = resolved.findSupertype(OrchestrationTask.class);
            }
            if (capability == null) capability = resolved.findSupertype(ExecutableTask.class);
            if (capability == null || capability.getTypeParameters().size() != 1
                || capability.getTypeParameters().getFirst().getErasedType() == Output.class) {
                throw new IllegalArgumentException("Task must declare a concrete Output type: " + type.getName());
            }
            var outputType = capability.getTypeParameters().getFirst();
            Map<String, Class<?>> result = new LinkedHashMap<>();
            for (var field : new MemberResolver(resolver).resolve(outputType, null, null).getMemberFields()) {
                var raw = field.getRawMember();
                if (raw.isSynthetic() || Modifier.isTransient(raw.getModifiers())
                    || raw.isAnnotationPresent(JsonIgnore.class) && raw.getAnnotation(JsonIgnore.class).value()) {
                    continue;
                }
                JsonProperty property = raw.getAnnotation(JsonProperty.class);
                String name = property == null || property.value().isEmpty()
                    ? raw.getName() : property.value();
                if (!name.equals("state") && !name.equals("error")) {
                    result.put(name, ReflectionUtils.getWrapperType(field.getType().getErasedType()));
                }
            }
            return Collections.unmodifiableMap(result);
        }
    };

    /** 不创建插件元数据工具实例。 */
    private TaskOutputs() {
    }

    /**
     * 读取 Task 代码中声明的结果字段，不创建 Task 或 Output 实例。
     * @param type 非 null 的具体 Task 类型
     * @return 缓存的不可变字段映射
     * @throws IllegalArgumentException 类型没有具体 Output 泛型时抛出
     */
    public static Map<String, Class<?>> fields(Class<? extends Task> type) {
        return fields.get(type);
    }

    /**
     * 判断任务声明的结果是否为 VoidOutput，包括泛型父类绑定的结果类型。
     * @param type 非 null 的任务类型
     * @return 声明无业务输出时为 true，未绑定具体结果时为 false
     */
    public static boolean isVoid(Class<? extends Task> type) {
        var capability = new TypeResolver().resolve(type).findSupertype(
            RunnableTask.class.isAssignableFrom(type) ? RunnableTask.class
                : ExecutableTask.class.isAssignableFrom(type) ? ExecutableTask.class : OrchestrationTask.class);
        return capability != null && capability.getTypeParameters().size() == 1
            && VoidOutput.class.isAssignableFrom(capability.getTypeParameters().getFirst().getErasedType());
    }

    /**
     * 将具体输出序列化为业务字段，控制状态、错误和未赋值字段不进入上下文。
     * @param output 非 null 的任务结果，只读
     * @return 新建的不可变业务映射；空结果返回空映射
     */
    public static Map<String, Object> values(Output output) {
        java.util.Objects.requireNonNull(output, "Task Output");
        if (output instanceof VoidOutput) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>(JsonObject.From(output).asMap());
        values.remove("state");
        values.remove("error");
        values.values().removeIf(java.util.Objects::isNull);
        return Map.copyOf(values);
    }

    /**
     * 将已有基础 Java 类型映射为表达式可比较的数据类型。
     * @param type 非 null 的 Java 类型
     * @return 对应基础类型；复合对象不参与现有标量条件比较，返回 null
     */
    public static DataType dataType(Class<?> type) {
        for (DataType candidate : DataType.values()) {
            if (candidate.getValueClass() == type) {
                return candidate;
            }
        }
        return null;
    }
}
