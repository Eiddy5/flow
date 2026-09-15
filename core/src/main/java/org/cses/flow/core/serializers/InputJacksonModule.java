package org.cses.flow.core.serializers;

import jakarta.inject.Singleton;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.plugins.InputTypes;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.deser.DeserializationProblemHandler;
import tools.jackson.databind.jsontype.NamedType;
import tools.jackson.databind.jsontype.TypeIdResolver;
import tools.jackson.databind.module.SimpleModule;

/** 为 PAAS 和 HTTP 的 Jackson 3 注册固定内置 Input。 */
@Singleton
public class InputJacksonModule extends SimpleModule {
    /**
     * Creates the reusable module for Jackson SPI and Micronaut discovery.
     */
    public InputJacksonModule() {
        super("flow-inputs");
    }

    /**
     * 注册固定内置名称并保留原有代码解析规则。
     * @param context Jackson 3 的模块装配上下文
     */
    @Override
    public void setupModule(SetupContext context) {
        super.setupModule(context);
        InputTypes.bindings().forEach((name, type) -> context.registerSubtypes(new NamedType(type, name)));
        context.addHandler(new DeserializationProblemHandler() {
            /**
             * 按固定类型表解析原有内置代码，未知类型交回 Jackson 报错。
             * @param context 当前绑定上下文，非空
             * @param baseType 声明的目标类型，非空
             * @param id 未识别的类型代码，只读
             * @param resolver Jackson 原生名称解析器，不修改
             * @param failure 原始错误说明，不修改
             * @return 匹配的内置类型；无匹配时返回 null，保留原始错误
             */
            @Override
            public JavaType handleUnknownTypeId(DeserializationContext context, JavaType baseType,
                                               String id, TypeIdResolver resolver, String failure) {
                Class<?> type = baseType.hasRawClass(Input.class) ? InputTypes.legacyType(id) : null;
                return type == null ? null : context.constructType(type);
            }
        });
    }
}
