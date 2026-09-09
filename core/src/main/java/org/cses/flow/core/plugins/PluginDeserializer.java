package org.cses.flow.core.plugins;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.cses.flow.core.domains.tasks.Task;
import org.paas.common.util.StringUtil;

import java.io.IOException;

import static java.util.Objects.requireNonNull;

/**
 * Resolves a plugin's exact class before Jackson binds its fields.
 */
public class PluginDeserializer<T extends Plugin> extends JsonDeserializer<T> {

    private static String TYPE = "type";


    private PluginRegistry registry;
    private boolean sourceDefinition;
    private Class<T> expectedType;


    public PluginDeserializer(PluginRegistry registry) {
        this(registry, false);
    }

    /**
     * 创建 Task 能力绑定器。
     * @param registry 非空只读插件注册表
     * @param sourceDefinition true 绑定源定义，false 恢复持久化身份
     */
    @SuppressWarnings("unchecked")
    PluginDeserializer(PluginRegistry registry, boolean sourceDefinition) {
        this(registry, sourceDefinition, (Class<T>) Task.class);
    }

    /**
     * 创建按目标能力解析类型的绑定器，不解释具体插件的业务规则。
     * @param registry 非空只读类型注册表
     * @param sourceDefinition true 时为 Task 生成首次身份，false 时恢复持久化身份
     * @param expectedType 非空的目标插件基类
     */
    PluginDeserializer(PluginRegistry registry, boolean sourceDefinition, Class<T> expectedType) {
        this.registry = requireNonNull(registry, "Plugin registry");
        this.sourceDefinition = sourceDefinition;
        this.expectedType = requireNonNull(expectedType, "Expected plugin type");
    }

    /**
     * 从对象 type 选择已注册的目标能力，再由具体类创建完整定义。
     * @param parser 当前 JSON/YAML 输入流，不由本方法关闭
     * @param context 当前绑定上下文，用于递归读取具体类
     * @return 已绑定的 Task 插件
     * @throws IOException 当对象格式、类型、能力或字段绑定非法时抛出
     */
    @Override
    @SuppressWarnings("unchecked")
    public T deserialize(
            JsonParser parser,
            DeserializationContext context
    ) throws IOException {
        JsonNode value = context.readTree(parser);
        if (!(value instanceof ObjectNode object)) {
            throw JsonMappingException.from(
                    parser,
                    "Plugin must be an object"
            );
        }
        JsonNode typeNode = object.get(TYPE);
        if (typeNode == null || !typeNode.isTextual()
                || typeNode.textValue().isBlank()) {
            throw context.weirdStringException(
                    typeNode == null ? null : typeNode.asText(),
                    Plugin.class,
                    "Plugin type must be non-blank text"
            );
        }

        String type = typeNode.textValue();
        Class<? extends Plugin> concreteType;
        try {
            concreteType = registry.resolve(type, expectedType);
        } catch (IllegalArgumentException exception) {
            throw context.invalidTypeIdException(
                    context.constructType(expectedType),
                    type,
                    exception.getMessage()
            );
        }

        ObjectNode fields = object.deepCopy();
        fields.remove("type");
        prepareSourceTask(fields, concreteType);
        return (T) context.readTreeAsValue(fields, concreteType);
    }

    private void prepareSourceTask(
            ObjectNode definition,
            Class<? extends Plugin> concreteType
    ) {
        if (!sourceDefinition
                || !Task.class.isAssignableFrom(concreteType)) {
            return;
        }

        rejectSystemField(definition, "id");
        rejectSystemField(definition, "parentId");
        rejectSystemField(definition, "taskId");

        JsonNode keyNode = definition.get("key");
        String key;
        if (keyNode == null || keyNode.isNull()) {
            key = StringUtil.newId();
        } else if (!keyNode.isTextual() || keyNode.textValue().isBlank()) {
            throw new IllegalArgumentException(
                    "Task key must be non-blank text"
            );
        } else {
            key = keyNode.textValue().trim();
        }

        definition.put("key", key);
        definition.put("id", StringUtil.newId());
    }

    private static void rejectSystemField(
            ObjectNode definition,
            String field
    ) {
        if (definition.has(field)) {
            throw new IllegalArgumentException(
                    "Task must not declare system field " + field
            );
        }
    }
}
