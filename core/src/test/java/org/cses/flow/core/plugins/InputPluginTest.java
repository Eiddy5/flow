package org.cses.flow.core.plugins;

import org.cses.flow.core.domains.flows.inputs.IntegerInput;
import io.micronaut.json.JsonMapper;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.serializers.JacksonMapper;
import org.cses.flow.core.serializers.PluginSchemaGenerator;
import org.cses.flow.core.serializers.YamlParser;
import org.cses.flow.extensions.log.Log;
import org.cses.flow.infrastructure.repositories.flows.codec.DataJsonCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.paas.json.JsonFactory;
import org.paas.json.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class InputPluginTest {
    private PluginRegistry registry;
    private JacksonMapper mapper;

    /** 配置真实注册与绑定链路，使用固定内置 Input。 */
    @BeforeEach
    void initialize() {
        JsonFactory.instance = JsonMapper.createDefault();
        registry = new DefaultPluginRegistry(List.of(new Log()));
        mapper = new JacksonMapper(new PluginModule(registry));
    }

    /** 固定目录只公布九种内置类型，Schema 不包含业务类型。 */
    @Test
    void describesOnlyFixedBuiltInInputs() {
        RegisteredPlugin group = registry.plugins().stream()
            .filter(value -> value.packageName().equals(IntegerInput.class.getPackageName()))
            .findFirst().orElseThrow();
        assertTrue(group.tasks().isEmpty());
        Set<String> names = Set.of("STRING", "BOOLEAN", "BYTE", "SHORT", "INTEGER", "LONG", "FLOAT", "DOUBLE", "CHARACTER");
        assertEquals(names, group.inputs().stream().map(PluginMetadata::typeName)
            .collect(java.util.stream.Collectors.toSet()));
        assertEquals(9, group.inputs().size());
        assertTrue(registry.findMetadata("BUSINESS_CODE").isEmpty());
        Map<String, Object> schema = new PluginSchemaGenerator(mapper, registry)
            .generate(registry.findMetadata("INTEGER").orElseThrow());
        Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
        assertTrue(properties.containsKey("min"));
        assertTrue(properties.containsKey("max"));
        assertFalse(properties.containsKey("valueType"));
        assertEquals(Set.of("INTEGER"), constants(schema));
        Map<String, Object> taskSchema = new PluginSchemaGenerator(mapper, registry)
            .generate(registry.findMetadata(Log.class.getCanonicalName()).orElseThrow());
        assertTrue(constants(taskSchema).containsAll(names));
        assertFalse(constants(taskSchema).contains("BUSINESS_CODE"));
    }

    /** 验证未知类型和错误能力均被拒绝。 */
    @Test
    void rejectsUnknownAndWrongCapabilityTypes() {
        String type = "INTEGER";
        for (String invalid : List.of("BUSINESS_CODE", "BusinessCodeInput", "java.lang.String", IntegerInput.class.getCanonicalName())) {
            assertThrows(IllegalArgumentException.class, () -> mapper.convertValue(definition(invalid), Input.class));
            assertThrows(RuntimeException.class,
                () -> YamlParser.parse(mapper.writeYaml(definition(invalid)), Input.class));
            assertThrows(RuntimeException.class, () -> JsonObject.FromMap(definition(invalid)).asObject(Input.class));
        }
        Map<String, Object> missing = definition(type);
        missing.remove("type");
        assertThrows(IllegalArgumentException.class, () -> mapper.convertValue(missing, Input.class));
        assertThrows(RuntimeException.class, () -> JsonObject.FromMap(missing).asObject(Input.class));
        assertThrows(IllegalArgumentException.class, () -> mapper.convertValue(definition(type), Task.class));
        assertThrows(IllegalArgumentException.class,
            () -> mapper.convertValue(definition(Log.class.getCanonicalName()), Input.class));

    }

    /** 验证 JSON/YAML、默认值、运行值和持久化全部保留内置类型及规则。 */
    @Test
    void bindsAndPersistsBuiltInDefinitionThroughEveryMapper() {
        Map<String, Object> definition = definition("INTEGER");
        Input<?> json = mapper.convertValue(JsonObject.FromMap(definition).asMap(), Input.class);
        Input<?> yaml = YamlParser.parse(mapper.writeYaml(definition), Input.class);
        assertEquals(json, yaml);
        assertEquals(json, JsonObject.FromMap(definition).asObject(Input.class));
        assertEquals(json, YamlParser.parse(mapper.writeYaml(json), Input.class));
        assertEquals(json, JsonObject.From(json).asObject(Input.class));
        assertEquals(12, json.getDefaultValue());
        assertEquals(34, json.bind(Map.of("employee", 34L)));
        assertThrows(IllegalArgumentException.class, () -> json.bind(Map.of("employee", 101)));
        List<Input<?>> restored = DataJsonCodec.decodeInputs(DataJsonCodec.encodeJsonb(List.of(json)), "Flow.inputs");
        assertEquals(json, restored.getFirst());
        assertEquals(56, restored.getFirst().bind(Map.of("employee", 56L)));
        Map<String, Object> serialized = JsonObject.From(json).asMap();
        assertEquals("INTEGER", serialized.get("type"));
        assertEquals(1, serialized.get("min"));
        assertEquals(100, serialized.get("max"));
        assertFalse(serialized.containsKey("valueType"));
    }

    /** 验证未知字段和非法内置默认值在物化返回前失败，并提供合法对照。 */
    @Test
    void rejectsInvalidDefinitionBeforeReturningFromEitherFormat() {
        Map<String, Object> definition = definition("INTEGER");
        assertDoesNotThrow(() -> mapper.convertValue(definition, Input.class));
        for (Map.Entry<String, Object> invalid : Map.<String, Object>of(
            "min", 101, "defaultValue", 101, "unknown", true, "key", 12).entrySet()) {
            Map<String, Object> copy = new LinkedHashMap<>(definition);
            copy.put(invalid.getKey(), invalid.getValue());
            assertThrows(IllegalArgumentException.class, () -> mapper.convertValue(copy, Input.class));
            assertThrows(RuntimeException.class, () -> YamlParser.parse(mapper.writeYaml(copy), Input.class));
            assertThrows(RuntimeException.class, () -> JsonObject.FromMap(copy).asObject(Input.class));
        }
    }

    /** 三个绑定入口保留内置短名称的历史大小写和空白兼容，写出统一名称。 */
    @Test
    void preservesLegacyBuiltInNamesThroughEveryMapper() {
        for (String type : List.of("string", " String ", "\tSTRING\n")) {
            Map<String, Object> definition = Map.of("type", type, "key", "reference", "defaultValue", "value");
            Input<?> expected = mapper.convertValue(definition, Input.class);
            assertInstanceOf(org.cses.flow.core.domains.flows.inputs.StringInput.class, expected);
            assertEquals(expected, YamlParser.parse(mapper.writeYaml(definition), Input.class));
            assertEquals(expected, JsonObject.FromMap(definition).asObject(Input.class));
            assertEquals("STRING", JsonObject.From(expected).asMap().get("type"));
        }
    }

    /** 持久化也拒绝完整类名和宿主自定义类型，避免恢复时绕过固定目录。 */
    @Test
    void rejectsNonBuiltInPersistedTypes() {
        for (String type : List.of("BUSINESS_CODE", IntegerInput.class.getCanonicalName())) {
            assertThrows(RuntimeException.class, () -> DataJsonCodec.decodeInputs(
                org.jooq.JSONB.valueOf("[" + JsonObject.FromMap(definition(type)).toJson() + "]"), "Flow.inputs"));
        }
    }

    /**
     * 收集整个 Schema 的常量，以检查嵌套输入类型和相互冲突的类型标识。
     * @param value 只读 Schema 子树，允许标量和 null
     * @return 本子树内出现的常量集合
     */
    private Set<Object> constants(Object value) {
        Set<Object> result = new HashSet<>();
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("const")) result.add(map.get("const"));
            map.values().forEach(nested -> result.addAll(constants(nested)));
        } else if (value instanceof List<?> list) {
            list.forEach(nested -> result.addAll(constants(nested)));
        }
        return result;
    }

    /**
     * 构造不共享可变状态的业务定义样本。
     * @param type 非空类型标识，允许测试传入不存在的类名
     * @return 新建的可变定义 Map
     */
    private Map<String, Object> definition(String type) {
        return new LinkedHashMap<>(Map.of("type", type, "key", "employee",
            "min", 1, "max", 100, "required", true, "defaultValue", 12.0));
    }
}
