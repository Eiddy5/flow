package org.cses.flow.core.plugins;

import io.micronaut.validation.validator.Validator;
import org.cses.flow.core.serializers.JacksonMapper;
import org.cses.flow.core.serializers.PluginSchemaGenerator;
import org.cses.flow.core.validations.ModelValidator;
import org.cses.flow.extensions.log.Log;
import org.cses.flow.extensions.flow.Parallel;
import org.cses.flow.extensions.flow.Pause;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginSchemaGeneratorTest {

    @Test
    void generatesLogMessageAsARequiredStringExpression() {
        DefaultPluginRegistry registry = new DefaultPluginRegistry(
            List.of(new Log())
        );
        ModelValidator validator = new ModelValidator(
            Validator.getInstance()
        );
        JacksonMapper mapper = new JacksonMapper(
            new PluginModule(registry, validator)
        );
        PluginMetadata<?> metadata = registry.findMetadata(
            Log.class.getCanonicalName()
        ).orElseThrow();

        Map<String, Object> schema = new PluginSchemaGenerator(mapper)
            .generate(metadata);
        Map<String, Object> properties = objectMap(
            schema.get("properties")
        );
        Map<String, Object> message = objectMap(
            properties.get("message")
        );

        assertEquals("日志", metadata.title());
        assertEquals(
            "解析消息表达式并将结果写入应用日志",
            metadata.description()
        );
        assertEquals("string", message.get("type"));
        assertEquals(
            List.of("处理结果：{{ dependOnOutputs.prepare.result }}"),
            message.get("examples")
        );
        assertTrue(((List<?>) schema.get("required")).contains("message"));
    }

    @Test
    void generatesTheConcreteTaskInputContractAndCachesIt() {
        DefaultPluginRegistry registry = new DefaultPluginRegistry(
            List.of(new TestNotificationTask())
        );
        ModelValidator validator = new ModelValidator(
            Validator.getInstance()
        );
        JacksonMapper mapper = new JacksonMapper(
            new PluginModule(registry, validator)
        );
        PluginSchemaGenerator generator = new PluginSchemaGenerator(mapper);
        PluginMetadata<?> metadata = registry.findMetadata(
            TestNotificationTask.class.getCanonicalName()
        ).orElseThrow();

        Map<String, Object> schema = generator.generate(metadata);

        assertEquals(
            "http://json-schema.org/draft-07/schema#",
            schema.get("$schema")
        );
        assertEquals(false, schema.get("additionalProperties"));
        Map<String, Object> properties = objectMap(
            schema.get("properties")
        );
        assertFalse(properties.containsKey("id"));
        assertTrue(properties.containsKey("key"));
        assertTrue(properties.containsKey("inputs"));
        assertTrue(properties.containsKey("outputs"));
        assertTrue(properties.containsKey("route"));
        assertTrue(properties.containsKey("dependOn"));
        assertTrue(properties.containsKey("tasks"));

        Map<String, Object> type = objectMap(properties.get("type"));
        assertEquals("string", type.get("type"));
        assertEquals(
            TestNotificationTask.class.getCanonicalName(),
            type.get("const")
        );
        Map<String, Object> channel = objectMap(
            properties.get("channel")
        );
        assertEquals("Channel", channel.get("title"));
        assertEquals(
            "Destination channel name.",
            channel.get("description")
        );
        assertEquals(
            List.of("flow-alerts"),
            channel.get("examples")
        );

        List<?> required = (List<?>) schema.get("required");
        assertTrue(required.contains("type"));
        assertTrue(required.contains("key"));
        assertTrue(required.contains("channel"));
        assertFalse(required.contains("id"));
        assertFalse(required.contains("inputs"));
        assertFalse(required.contains("outputs"));
        assertFalse(required.contains("route"));
        assertFalse(required.contains("dependOn"));
        assertFalse(required.contains("tasks"));

        assertSame(schema, generator.generate(metadata));
        assertThrows(
            UnsupportedOperationException.class,
            () -> schema.put("changed", true)
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> properties.put("changed", Map.of())
        );
    }

    @Test
    void parallelConcurrentIsOptionalAndMustBePositive() {
        DefaultPluginRegistry registry = new DefaultPluginRegistry(
            List.of(new Parallel())
        );
        ModelValidator validator = new ModelValidator(
            Validator.getInstance()
        );
        JacksonMapper mapper = new JacksonMapper(
            new PluginModule(registry, validator)
        );
        PluginMetadata<?> metadata = registry.findMetadata(
            Parallel.class.getCanonicalName()
        ).orElseThrow();

        Map<String, Object> schema = new PluginSchemaGenerator(mapper)
            .generate(metadata);
        Map<String, Object> concurrent = objectMap(
            objectMap(schema.get("properties")).get("concurrent")
        );

        assertEquals("integer", concurrent.get("type"));
        assertEquals(
            0,
            ((Number) concurrent.get("exclusiveMinimum")).intValue()
        );
        assertFalse(
            ((List<?>) schema.get("required")).contains("concurrent")
        );
        assertThrows(
            RuntimeException.class,
            () -> mapper.convertValue(
                Map.of(
                    "id", "parallel-id",
                    "key", "parallel",
                    "type", Parallel.class.getCanonicalName(),
                    "concurrent", 0
                ),
                org.cses.flow.core.domains.tasks.Task.class
            )
        );
    }

    @Test
    void pauseSchemaComesFromItsConcreteDefinitionFields() {
        DefaultPluginRegistry registry = new DefaultPluginRegistry(
            List.of(new Pause())
        );
        ModelValidator validator = new ModelValidator(
            Validator.getInstance()
        );
        JacksonMapper mapper = new JacksonMapper(
            new PluginModule(registry, validator)
        );
        PluginMetadata<?> metadata = registry.findMetadata(
            Pause.class.getCanonicalName()
        ).orElseThrow();

        Map<String, Object> schema = new PluginSchemaGenerator(mapper)
            .generate(metadata);
        Map<String, Object> properties = objectMap(
            schema.get("properties")
        );
        List<?> required = (List<?>) schema.get("required");

        assertEquals("暂停", metadata.title());
        assertTrue(properties.containsKey("pause"));
        assertTrue(properties.containsKey("resume"));
        assertTrue(properties.containsKey("duration"));
        assertTrue(properties.containsKey("behavior"));
        assertTrue(required.contains("pause"));
        assertFalse(required.contains("resume"));
        assertFalse(required.contains("duration"));
        assertFalse(required.contains("behavior"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        return (Map<String, Object>) value;
    }
}
