package org.cses.flow.core.domains.flows;

import io.micronaut.json.JsonMapper;
import org.cses.flow.core.domains.flows.inputs.IntegerInput;
import org.cses.flow.core.domains.flows.inputs.StringInput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonFactory;
import org.paas.json.JsonObject;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataTest {

    @BeforeAll
    static void initializeJsonMapper() {
        JsonFactory.instance = JsonMapper.createDefault();
    }

    @Test
    void dataContractExposesOnlyKeyAndType() {
        Set<String> methods = Arrays.stream(Data.class.getDeclaredMethods())
            .map(method -> method.getName())
            .collect(Collectors.toSet());

        assertEquals(Set.of("getKey", "getType"), methods);
    }

    @Test
    void dataTypeIsIndependentAndKeepsOnlyValueSemantics() {
        assertEquals(0, Data.class.getDeclaredClasses().length);
        assertEquals(
            Set.of(
                DataType.STRING,
                DataType.BOOLEAN,
                DataType.BYTE,
                DataType.SHORT,
                DataType.INTEGER,
                DataType.LONG,
                DataType.FLOAT,
                DataType.DOUBLE,
                DataType.CHARACTER
            ),
            Set.of(DataType.values())
        );
        assertEquals(Integer.class, DataType.INTEGER.getValueClass());
        assertTrue(DataType.INTEGER.accepts(1));
        assertTrue(!DataType.INTEGER.accepts(1L));
    }

    @Test
    void deserializesConcreteInputsAndValidatesIntegerRules() {
        Input<?> input = jsonInput(
            Map.of(
                "key", " retryCount ",
                "type", "INTEGER",
                "displayName", " 重试次数 ",
                "required", true,
                "defaultValue", 3,
                "min", 0,
                "max", 10
            )
        );
        IntegerInput integerInput = assertInstanceOf(
            IntegerInput.class,
            input
        );

        assertEquals("retryCount", integerInput.getKey());
        assertEquals("重试次数", integerInput.getDisplayName());
        assertEquals(DataType.INTEGER, integerInput.getType());
        assertEquals(3, integerInput.getDefaultValue());
        assertEquals(0, integerInput.getMin());
        assertEquals(10, integerInput.getMax());
        assertDoesNotThrow(() -> integerInput.valid(10));
        assertThrows(
            IllegalArgumentException.class,
            () -> integerInput.valid(11)
        );
        assertEquals(
            integerInput,
            JsonObject.From(integerInput).asObject(Input.class)
        );
    }

    @Test
    void deserializesEverySimpleInputTypeWithoutPublicCreate() {
        Map<DataType, Object> defaults = Map.of(
            DataType.STRING, "text",
            DataType.BOOLEAN, true,
            DataType.BYTE, 1,
            DataType.SHORT, 2,
            DataType.INTEGER, 3,
            DataType.LONG, 4L,
            DataType.FLOAT, 1.5,
            DataType.DOUBLE, 2.5,
            DataType.CHARACTER, 'A'
        );

        for (DataType type : DataType.values()) {
            Input<?> input = jsonInput(
                Map.of(
                    "key", type.name().toLowerCase(),
                    "type", type.name(),
                    "displayName", type.name(),
                    "required", false,
                    "defaultValue", defaults.get(type)
                )
            );
            assertEquals(type, input.getType());
            assertTrue(
                input.getClass().getSimpleName().endsWith("Input")
            );
            assertEquals(
                input,
                JsonObject.From(input).asObject(Input.class)
            );
        }
    }

    @Test
    void superBuilderBuildsInheritedInputFields() {
        StringInput input = StringInput.builder()
            .key(" request ")
            .displayName(" 请求 ")
            .required(false)
            .defaultValue("payload")
            .build();

        input.validateDefinition();

        assertEquals("request", input.getKey());
        assertEquals("请求", input.getDisplayName());
        assertEquals("payload", input.getDefaultValue());
        assertEquals(DataType.STRING, input.getType());
    }

    @Test
    void settersSupportFrameworkStylePropertyBinding() {
        IntegerInput input = new IntegerInput();
        input.setKey(" retryCount ");
        input.setDisplayName(" 重试次数 ");
        input.setRequired(true);
        input.setDefaultValue(3);
        input.setMin(0);
        input.setMax(10);

        input.validateDefinition();

        assertEquals("retryCount", input.getKey());
        assertEquals("重试次数", input.getDisplayName());
        assertEquals(3, input.getDefaultValue());
        assertEquals(0, input.getMin());
        assertEquals(10, input.getMax());
    }

    @Test
    void jsonPolymorphismRequiresCompleteCommonFields() {
        assertThrows(
            RuntimeException.class,
            () -> jsonInput(
                Map.of(
                    "key", "request",
                    "type", "STRING",
                    "displayName", "",
                    "required", false
                )
            )
        );
    }

    @Test
    void rejectsUnknownTypesAndSubtypeFieldMismatches() {
        assertThrows(
            IllegalArgumentException.class,
            () -> DataType.parse("JSON")
        );
        assertThrows(
            RuntimeException.class,
            () -> jsonInput(
                Map.of(
                    "key", "value",
                    "type", "STRING",
                    "displayName", "值",
                    "required", false,
                    "min", 0
                )
            )
        );
        assertThrows(
            RuntimeException.class,
            () -> jsonInput(
                Map.of(
                    "key", "value",
                    "type", "INTEGER",
                    "displayName", "值",
                    "required", false,
                    "min", 2,
                    "max", 1
                )
            )
        );
    }

    @Test
    void outputUsesTheIndependentDataType() {
        Output output = Output.create(
            " result ",
            DataType.STRING
        );

        assertEquals("result", output.getKey());
        assertEquals(DataType.STRING, output.getType());
        assertEquals(
            output,
            Output.rehydrate("result", DataType.STRING)
        );
        assertDoesNotThrow(() -> output.valid("done"));
        assertThrows(
            IllegalArgumentException.class,
            () -> output.valid(1)
        );
        assertNull(
            JsonObject.From(jsonInput(Map.of(
                "key", "optional",
                "type", "STRING",
                "displayName", "可选值",
                "required", false
            ))).asMap().get("defaultValue")
        );
    }

    private static Input<?> jsonInput(Map<String, Object> definition) {
        Input<?> input = JsonObject.FromMap(definition)
            .asObject(Input.class);
        input.validateDefinition();
        return input;
    }
}
