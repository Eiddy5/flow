package org.cses.flow.core.plugins;

import com.example.flow.inputs.BusinessCodeInput;
import com.fasterxml.jackson.annotation.JsonTypeName;
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
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.File;
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

    /** 配置真实注册与绑定链路，自动发现外部包业务类。 */
    @BeforeEach
    void initialize() {
        JsonFactory.instance = JsonMapper.createDefault();
        registry = new DefaultPluginRegistry(List.of(new Log()));
        mapper = new JacksonMapper(new PluginModule(registry));
    }

    /** 验证编译期发现、目录和 Schema 不需要创建无参数业务 Input。 */
    @Test
    void discoversAndDescribesExternalClassWithoutEmptyInstance() {
        assertEquals(0, BusinessCodeInput.class.getConstructors().length);
        assertEquals("BUSINESS_CODE", BusinessCodeInput.class.getDeclaredAnnotation(JsonTypeName.class).value());
        assertEquals(List.of(JsonTypeName.class), java.util.Arrays.stream(
            BusinessCodeInput.class.getDeclaredAnnotations()).map(annotation -> annotation.annotationType()).toList());
        assertSame(BusinessCodeInput.class,
            registry.resolve(BusinessCodeInput.class.getCanonicalName(), Input.class));
        RegisteredPlugin group = registry.plugins().stream()
            .filter(value -> value.packageName().equals(BusinessCodeInput.class.getPackageName()))
            .findFirst().orElseThrow();
        assertTrue(group.tasks().isEmpty());
        assertEquals(1, group.inputs().size());
        assertEquals("BUSINESS_CODE", group.inputs().getFirst().typeName());
        Map<String, Object> schema = new PluginSchemaGenerator(mapper, registry)
            .generate(group.inputs().getFirst());
        Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
        assertTrue(properties.containsKey("prefix"));
        assertFalse(properties.containsKey("valueType"));
        assertEquals("BUSINESS_CODE",
            ((Map<?, ?>) properties.get("type")).get("const"));
        assertEquals(Set.of("BUSINESS_CODE"), constants(schema));
        Map<String, Object> taskSchema = new PluginSchemaGenerator(mapper, registry)
            .generate(registry.findMetadata(Log.class.getCanonicalName()).orElseThrow());
        assertTrue(constants(taskSchema).contains("BUSINESS_CODE"));
        assertTrue(constants(taskSchema).contains("STRING"));
        assertFalse(constants(taskSchema).contains("BusinessCodeInput"));
    }

    /** 验证精确类型、能力和重复注册均不可被错误类型绕过。 */
    @Test
    void rejectsUnknownDuplicateAndWrongCapabilityTypes() {
        String type = "BUSINESS_CODE";
        for (String invalid : List.of(type.toLowerCase(), " " + type, "BusinessCodeInput", "java.lang.String")) {
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
        assertThrows(IllegalStateException.class, () -> new DefaultPluginRegistry(List.of(),
            List.of(BusinessCodeInput.class, BusinessCodeInput.class)));
        assertThrows(IllegalStateException.class,
            () -> new DefaultPluginRegistry(List.of(), List.of(String.class)));
    }

    /** 验证 JSON/YAML、默认值、运行值和持久化全部保留业务类型及规则。 */
    @Test
    void bindsAndPersistsExternalDefinitionThroughTheSameInput() {
        Map<String, Object> definition = definition("BUSINESS_CODE");
        Input<?> json = mapper.convertValue(JsonObject.FromMap(definition).asMap(), Input.class);
        Input<?> yaml = YamlParser.parse(mapper.writeYaml(definition), Input.class);
        assertEquals(json, yaml);
        assertEquals(json, JsonObject.FromMap(definition).asObject(Input.class));
        assertEquals(json, YamlParser.parse(mapper.writeYaml(json), Input.class));
        assertEquals(json, JsonObject.From(json).asObject(Input.class));
        assertEquals("EMP-12", json.getDefaultValue());
        assertEquals("EMP-34", json.bind(Map.of("employee", " emp-34 ")));
        assertThrows(IllegalArgumentException.class, () -> json.bind(Map.of("employee", "ORG-34")));
        List<Input<?>> restored = DataJsonCodec.decodeInputs(DataJsonCodec.encodeJsonb(List.of(json)), "Flow.inputs");
        assertEquals(json, restored.getFirst());
        assertEquals("EMP-56", restored.getFirst().bind(Map.of("employee", "emp-56")));
        Map<String, Object> serialized = JsonObject.From(json).asMap();
        assertEquals("BUSINESS_CODE", serialized.get("type"));
        assertEquals("EMP", serialized.get("prefix"));
        assertFalse(serialized.containsKey("valueType"));
    }

    /** 验证未知字段和非法业务默认值在物化返回前失败，并提供合法对照。 */
    @Test
    void rejectsInvalidDefinitionBeforeReturningFromEitherFormat() {
        Map<String, Object> definition = definition("BUSINESS_CODE");
        assertDoesNotThrow(() -> mapper.convertValue(definition, Input.class));
        for (Map.Entry<String, Object> invalid : Map.<String, Object>of(
            "prefix", "!", "defaultValue", "OTHER-1", "unknown", true, "key", 12).entrySet()) {
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

    /** 验证历史完整类名可读，但所有写出都使用稳定业务名称。 */
    @Test
    void readsLegacyCanonicalAliasAndWritesOnlyTheDeclaredName() {
        Map<String, Object> legacy = definition(BusinessCodeInput.class.getCanonicalName());
        Input<?> expected = mapper.convertValue(definition("BUSINESS_CODE"), Input.class);
        Input<?> restored = mapper.convertValue(legacy, Input.class);
        assertEquals(expected, restored);
        assertEquals(expected, YamlParser.parse(mapper.writeYaml(legacy), Input.class));
        assertEquals(expected, JsonObject.FromMap(legacy).asObject(Input.class));
        assertEquals(expected, DataJsonCodec.decodeInputs(
            org.jooq.JSONB.valueOf("[" + JsonObject.FromMap(legacy).toJson() + "]"), "Flow.inputs").getFirst());
        assertEquals("BUSINESS_CODE", mapper.toMap(restored).get("type"));
        assertEquals("BUSINESS_CODE", JsonObject.From(restored).asMap().get("type"));
        assertFalse(mapper.writeYaml(restored).contains(BusinessCodeInput.class.getCanonicalName()));
    }

    /**
     * 模拟另一个宿主 JAR 的同名类型，验证注册拒绝而不调用构造器。
     * @param directory JUnit 提供的独立临时输出目录，写入临时源码和字节码
     * @throws Exception 临时编译或类加载失败时抛出
     */
    @Test
    void rejectsDuplicateDeclaredNamesAcrossHostClasses(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("DuplicateInput.java");
        Files.writeString(source, """
            package example.conflict;
            @com.fasterxml.jackson.annotation.JsonTypeName("BUSINESS_CODE")
            public class DuplicateInput extends org.cses.flow.core.domains.flows.Input<String> {
                public DuplicateInput() {
                    super("duplicate", null, false, null);
                    throw new AssertionError("Discovery must not construct inputs");
                }
                public org.cses.flow.core.domains.flows.DataType getValueType() {
                    return org.cses.flow.core.domains.flows.DataType.STRING;
                }
                protected String convert(Object value) { return (String) value; }
            }
            """);
        String classpath = Path.of(Input.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            + File.pathSeparator
            + Path.of(JsonTypeName.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            + File.pathSeparator
            + Path.of(org.paas.json.SerializableObject.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-proc:none", "-classpath", classpath, "-d", directory.toString(), source.toString()));
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()},
            Input.class.getClassLoader())) {
            Class<?> duplicate = loader.loadClass("example.conflict.DuplicateInput");
            IllegalStateException rejected = assertThrows(IllegalStateException.class,
                () -> new DefaultPluginRegistry(List.of(), List.of(BusinessCodeInput.class, duplicate)));
            assertTrue(rejected.getMessage().contains("BUSINESS_CODE"), rejected.getMessage());
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
            "prefix", "EMP", "required", true, "defaultValue", " emp-12 "));
    }
}
