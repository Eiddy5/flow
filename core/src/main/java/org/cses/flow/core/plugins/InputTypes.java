package org.cses.flow.core.plugins;

import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.inputs.*;

import java.util.Map;

/** 固定的九种内置 Input 类型，供 JSON、YAML 和类型目录共用。 */
public class InputTypes {
    private static Map<String, Class<? extends Input>> bindings = Map.of(
        "STRING", StringInput.class,
        "BOOLEAN", BooleanInput.class,
        "BYTE", ByteInput.class,
        "SHORT", ShortInput.class,
        "INTEGER", IntegerInput.class,
        "LONG", LongInput.class,
        "FLOAT", FloatInput.class,
        "DOUBLE", DoubleInput.class,
        "CHARACTER", CharacterInput.class
    );

    /**
     * 返回内置类的固定代码，不接受宿主自定义子类。
     * @param type 待查询的具体类，只读；null 和非内置类均拒绝
     * @return 内置类型的大写代码
     * @throws IllegalStateException 当类型不属于内置清单时抛出
     */
    public static String name(Class<?> type) {
        return bindings.entrySet().stream()
            .filter(entry -> entry.getValue().equals(type))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Unsupported Input type: " + type));
    }

    /** @return 九种内置类型的只读映射，不包含业务类型或完整类名别名 */
    public static Map<String, Class<? extends Input>> bindings() {
        return bindings;
    }

    /**
     * 按已有 DataType 规则解析内置代码，保留大小写和首尾空白规则。
     * @param name 待解析的类型代码，只读，可以为 null
     * @return 匹配的内置类；未知或空代码返回 null，由绑定入口报告错误
     */
    public static Class<? extends Input> legacyType(String name) {
        try {
            return bindings.get(DataType.parse(name).name());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
