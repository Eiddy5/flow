package org.cses.flow.core.domains.flows;

import io.micronaut.json.JsonMapper;
import org.cses.flow.core.domains.flows.inputs.IntegerInput;
import org.cses.flow.core.domains.flows.inputs.StringInput;
import org.cses.flow.core.serializers.YamlParser;
import org.cses.flow.infrastructure.repositories.flows.codec.DataJsonCodec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonFactory;
import org.paas.json.JsonObject;
import org.paas.json.JsonObjects;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InputBindingTest {

    /** 配置与既有 Data 技术测试相同的 PAAS JSON 绑定入口。 */
    @BeforeAll
    static void initializeJson() {
        JsonFactory.instance = JsonMapper.createDefault();
        org.cses.flow.core.plugins.TaskPluginTestSupport.builtInContext();
    }

    /** 验证缺失与显式 null 的区别、默认值、必填规则及提交映射只读。 */
    @Test
    void inputOwnsPresenceDefaultsAndRequiredRules() {
        StringInput optional = StringInput.builder()
            .key("person").defaultValue("fallback").build();
        StringInput required = StringInput.builder()
            .key("person").required(true).defaultValue("fallback").build();
        Map<String, Object> submitted = new LinkedHashMap<>();
        submitted.put("person", null);

        assertEquals("fallback", optional.bind(Map.of()));
        assertEquals("fallback", required.bind(Map.of()));
        assertNull(optional.bind(submitted));
        assertThrows(IllegalArgumentException.class, () -> required.bind(submitted));
        assertEquals("alice", optional.bind(Map.of("person", "alice")));
        assertTrue(submitted.containsKey("person"));
        assertNull(submitted.get("person"));
        assertEquals("fallback", optional.getDefaultValue());
        assertThrows(IllegalArgumentException.class, () -> StringInput.builder()
            .key("person").required(true).build().bind(Map.of()));
    }

    /** 验证每个具体 Input 自行完成传输值转换，拒绝不兼容的值。 */
    @Test
    void concreteInputsBindAllSupportedTransportValues() {
        Map<DataType, Object> raw = Map.of(
            DataType.STRING, "value", DataType.BOOLEAN, true,
            DataType.BYTE, 7, DataType.SHORT, 7, DataType.INTEGER, 7L,
            DataType.LONG, 7, DataType.FLOAT, 1.5D,
            DataType.DOUBLE, 7, DataType.CHARACTER, "A"
        );
        Map<DataType, Object> expected = Map.of(
            DataType.STRING, "value", DataType.BOOLEAN, true,
            DataType.BYTE, (byte) 7, DataType.SHORT, (short) 7,
            DataType.INTEGER, 7, DataType.LONG, 7L,
            DataType.FLOAT, 1.5F, DataType.DOUBLE, 7D, DataType.CHARACTER, 'A'
        );
        for (DataType type : DataType.values()) {
            Input<?> input = JsonObject.FromMap(Map.of(
                "key", "value", "type", type.name(), "required", true
            )).asObject(Input.class);
                assertEquals(expected.get(type), input.bind(Map.of("value", raw.get(type))));
            assertThrows(IllegalArgumentException.class, () -> input.bind(Map.of()));
            assertThrows(IllegalArgumentException.class, () -> input.bind(Map.of("value", List.of())));
        }
    }

    /** 验证整数范围在同一个绑定入口生效，且错误可以定位到具体字段。 */
    @Test
    void integerBindingProtectsConversionBoundsAndDefaults() {
        IntegerInput input = IntegerInput.builder()
            .key("attempts").min(1).max(3).defaultValue(2).build();
        assertEquals(2, input.bind(Map.of()));
        assertEquals(3, input.bind(Map.of("attempts", 3L)));
        for (Object invalid : List.of(0, 4, 1.5, Long.MAX_VALUE, "2")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> input.bind(Map.of("attempts", invalid)));
            assertTrue(failure.getMessage().contains("attempts"));
        }
        assertThrows(IllegalArgumentException.class, () -> IntegerInput.builder()
            .key("attempts").min(3).max(1).build());
        assertThrows(IllegalArgumentException.class, () -> IntegerInput.builder()
            .key("attempts").max(1).defaultValue(2).build());
    }

    /** 验证同类型浮点值也不能绕过具体 Input 的有限值约束。 */
    @Test
    void floatingInputsRejectNonFiniteValuesAndDefaults() {
        assertThrows(IllegalArgumentException.class, () -> org.cses.flow.core.domains.flows.inputs.FloatInput
            .builder().key("amount").defaultValue(Float.NaN).build());
        assertThrows(IllegalArgumentException.class, () -> org.cses.flow.core.domains.flows.inputs.DoubleInput
            .builder().key("amount").defaultValue(Double.POSITIVE_INFINITY).build());
        for (DataType type : List.of(DataType.FLOAT, DataType.DOUBLE)) {
            Input<?> input = JsonObject.FromMap(Map.of("key", "amount", "type", type.name()))
                .asObject(Input.class);
            for (Object value : List.of(Float.NaN, Float.POSITIVE_INFINITY,
                    Double.NaN, Double.NEGATIVE_INFINITY)) {
                assertThrows(IllegalArgumentException.class, () -> input.bind(Map.of("amount", value)));
            }
        }
    }

    /** 验证 YAML 与持久化默认值均由 Input 严格转换，不接受框架的宽松强制转换。 */
    @Test
    void definitionBindingKeepsDefaultsStrictAcrossYamlAndPersistence() {
        for (Object invalid : List.of("2", 2.5, 2147483648L)) {
            assertThrows(IllegalStateException.class, () -> DataJsonCodec.decodeInputs(
                JsonObjects.FromList(List.of(Map.of(
                    "key", "attempts", "type", "INTEGER", "defaultValue", invalid
                ))), "Flow.inputs"));
        }
        assertThrows(RuntimeException.class, () -> YamlParser.parse("""
            key: attempts
            type: INTEGER
            defaultValue: '2'
            """, Input.class));
        Input<?> input = DataJsonCodec.decodeInputs(JsonObjects.FromList(List.of(Map.of(
            "key", "attempts", "type", " integer ", "defaultValue", 2L,
            "min", 1, "max", 3
        ))), "Flow.inputs").getFirst();
        assertEquals("attempts", input.getDisplayName());
        assertEquals(2, input.bind(Map.of()));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> DataJsonCodec.decodeInputs(JsonObjects.FromList(List.of(Map.of(
                "key", "attempts", "type", "INTEGER", "defaultValue", 4, "max", 3
            ))), "Flow.inputs"));
        assertTrue(failure.getMessage().contains("Flow.inputs[0]"));
    }

    /** 验证移除 Codec 字段预处理后，Input 仍拒绝非文本或空白字段标识。 */
    @Test
    void inputOwnsStrictDefinitionKeyBinding() {
        for (Object invalid : List.of(1, true, " ")) {
            assertThrows(IllegalStateException.class, () -> DataJsonCodec.decodeInputs(
                JsonObjects.FromList(List.of(Map.of("key", invalid, "type", "STRING"))),
                "Flow.inputs"));
        }
        assertThrows(RuntimeException.class, () -> YamlParser.parse("""
            key: 123
            type: STRING
            """, Input.class));
    }

    /** 验证两种反序列化入口返回时已完成完整约束检查，与属性顺序无关。 */
    @Test
    void deserializationReturnsOnlyCompleteValidInputDefinitions() {
        for (boolean reversed : List.of(false, true)) {
            Map<String, Object> definition = new LinkedHashMap<>();
            if (reversed) {
                definition.put("max", 3);
            }
            definition.put("key", " attempts ");
            definition.put("type", "INTEGER");
            definition.put("defaultValue", 4);
            definition.put("min", 1);
            definition.put("max", 3);
            assertThrows(RuntimeException.class,
                () -> JsonObject.FromMap(definition).asObject(Input.class));
            assertThrows(RuntimeException.class,
                () -> JsonObject.FromMap(definition).asObject(IntegerInput.class));
        }
        assertThrows(RuntimeException.class, () -> YamlParser.parse("""
            key: attempts
            type: INTEGER
            defaultValue: 4
            min: 1
            max: 3
            """, Input.class));
        Input<?> input = JsonObject.FromMap(Map.of(
            "key", " attempts ", "type", "INTEGER", "defaultValue", 2, "min", 1, "max", 3
        )).asObject(Input.class);
        assertEquals("attempts", input.getKey());
        assertEquals("attempts", input.getDisplayName());
        assertEquals(2, input.getDefaultValue());
        assertFalse(input.isRequired());
    }

    /** 验证嵌套列表内的 Input 在所属 Flow/Task/Pause 返回前拒绝非法定义。 */
    @Test
    void nestedDefinitionsFailDuringDeserializationWithoutExternalValidation() {
        String flowSource = """
            key: example
            inputs:
              - key: attempts
                type: INTEGER
                min: 3
                max: 1
            """;
        assertDoesNotThrow(() -> YamlParser.parse(flowSource.replace("max: 1", "max: 3"), Flow.class));
        assertThrows(RuntimeException.class, () -> YamlParser.parse(flowSource, Flow.class));
        String taskSource = """
            key: step
            inputs:
              - key: attempts
                type: INTEGER
                defaultValue: 4
                max: 3
            """;
        assertDoesNotThrow(() -> YamlParser.parse(taskSource.replace("max: 3", "max: 4"),
            org.cses.flow.extensions.log.Log.class));
        assertThrows(RuntimeException.class, () -> YamlParser.parse(taskSource,
            org.cses.flow.extensions.log.Log.class));
        String pauseSource = """
            key: wait
            onResume:
              - key: attempts
                type: INTEGER
                defaultValue: 4
                max: 3
            """;
        assertDoesNotThrow(() -> YamlParser.parse(pauseSource.replace("max: 3", "max: 4"),
            org.cses.flow.extensions.flow.Pause.class));
        assertThrows(RuntimeException.class, () -> YamlParser.parse(pauseSource,
            org.cses.flow.extensions.flow.Pause.class));
    }
}
