package org.cses.flow.core.plugins;

import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.domains.flows.Input;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * All registered plugin capabilities declared in one real Java package.
 */
public record RegisteredPlugin(
    String packageName,
    List<PluginMetadata<Task>> tasks,
    List<PluginMetadata<Input>> inputs
) {

    public static RegisteredPlugin from(
        String packageName,
        List<PluginMetadata<Task>> tasks
    ) {
        return new RegisteredPlugin(packageName, tasks, List.of());
    }

    /**
     * 创建包含 Task 和业务 Input 的真实包目录。
     * @param packageName 非空真实包名
     * @param tasks 非空 Task 元信息列表，只读复制
     * @param inputs 非空 Input 元信息列表，只读复制
     * @return 排序后的只读目录
     * @throws IllegalArgumentException 当元信息归属包错误时抛出
     */
    public static RegisteredPlugin from(String packageName, List<PluginMetadata<Task>> tasks,
                                        List<PluginMetadata<Input>> inputs) {
        return new RegisteredPlugin(packageName, tasks, inputs);
    }

    /**
     * 校验包归属并复制、排序两类元信息，返回后调用方不能修改目录列表。
     * @param packageName 非空白真实包名
     * @param tasks 非空且元素非空的 Task 元信息，必须属于该包
     * @param inputs 非空且元素非空的 Input 元信息，必须属于该包
     * @throws IllegalArgumentException 当包名或元信息归属非法时抛出
     */
    public RegisteredPlugin {
        if (packageName == null || packageName.isBlank()) {
            throw new IllegalArgumentException(
                "Registered plugin package name must not be blank"
            );
        }
        Objects.requireNonNull(tasks, "Registered plugin tasks");
        if (tasks.stream().anyMatch(task ->
            !packageName.equals(task.packageName())
        )) {
            throw new IllegalArgumentException(
                "Registered plugin tasks must belong to package: "
                    + packageName
            );
        }
        Objects.requireNonNull(inputs, "Registered plugin inputs");
        if (inputs.stream().anyMatch(input -> !packageName.equals(input.packageName()))) {
            throw new IllegalArgumentException("Registered plugin inputs must belong to package: " + packageName);
        }
        inputs = inputs.stream().sorted(Comparator.comparing(PluginMetadata::typeName)).toList();
        tasks = tasks.stream()
            .sorted(Comparator.comparing(PluginMetadata::typeName))
            .toList();
    }
}
