package org.cses.flow.core.plugins;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.jsontype.TypeIdResolver;
import org.cses.flow.core.domains.flows.Input;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.tasks.Task;

import java.io.Serial;

import static java.util.Objects.requireNonNull;

/**
 * Installs strict polymorphic binding for supported plugin capabilities.
 */
@Singleton
public class PluginModule extends SimpleModule {

    @Serial
    private static long serialVersionUID = 1L;
    private PluginRegistry registry;
    private InputTypes inputTypes;

    public PluginModule(PluginRegistry registry) {
        this(registry, false);
    }

    /**
     * 安装 Task 绑定和 Input 原生 Jackson 子类型配置。
     * @param registry 非空只读类型注册表
     * @param sourceDefinition true 时处理新 Task 身份，false 时恢复已存身份
     */
    private PluginModule(
        PluginRegistry registry,
        boolean sourceDefinition
    ) {
        super("flow-plugin");
        this.registry = requireNonNull(registry, "Plugin registry");
        inputTypes = new InputTypes(registry.plugins().stream()
            .flatMap(group -> group.inputs().stream()).<Class<?>>map(PluginMetadata::type).toList());
        inputTypes.bindings().forEach((name, type) -> registerSubtypes(new NamedType(type, name)));
        addDeserializer(
            Task.class,
            new PluginDeserializer<>(registry, sourceDefinition)
        );
    }

    /**
     * 安装原生子类型绑定，并仅为历史内置代码保留大小写和空白兼容。
     * @param context 当前 Mapper 的模块装配上下文
     */
    @Override
    public void setupModule(SetupContext context) {
        super.setupModule(context);
        context.addDeserializationProblemHandler(new DeserializationProblemHandler() {
            /**
             * 为未知的历史内置代码提供已注册的具体类型，其他错误交回 Jackson。
             * @param context 当前反序列化上下文
             * @param baseType 目标基类
             * @param id 未识别的类型名称
             * @param resolver Jackson 原生名称解析器
             * @param failure 原生错误说明
             * @return 匹配的内置类型，无匹配时为 null
             */
            @Override
            public JavaType handleUnknownTypeId(DeserializationContext context, JavaType baseType,
                                               String id, TypeIdResolver resolver, String failure) {
                Class<?> type = baseType.hasRawClass(Input.class) ? inputTypes.legacyType(id) : null;
                return type == null ? null : context.constructType(type);
            }
        });
    }

    public PluginModule sourceDefinitions() {
        return new PluginModule(registry, true);
    }
}
