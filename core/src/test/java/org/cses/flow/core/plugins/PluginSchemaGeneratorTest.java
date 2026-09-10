package org.cses.flow.core.plugins;

import org.cses.flow.core.serializers.JacksonMapper;
import org.cses.flow.core.serializers.PluginSchemaGenerator;
import org.cses.flow.extensions.log.Log;
import org.cses.flow.extensions.flow.Loop;
import org.cses.flow.extensions.flow.LoopUntil;
import org.cses.flow.extensions.flow.Parallel;
import org.cses.flow.extensions.flow.Pause;
import org.cses.flow.extensions.flow.Route;
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
        DefaultPluginRegistry registry = registry(new Log());
        JacksonMapper mapper = new JacksonMapper(
            new PluginModule(registry)
        );
        PluginMetadata<?> metadata = registry.findMetadata(
            Log.class.getCanonicalName()
        ).orElseThrow();

        Map<String, Object> schema = new PluginSchemaGenerator(mapper, registry)
            .generate(metadata);
        Map<String, Object> properties = objectMap(
            schema.get("properties")
        );
        Map<String, Object> message = objectMap(
            properties.get("message")
        );

        assertEquals("日志", metadata.title());
        assertEquals(
            "将模板消息写入应用日志",
            metadata.description()
        );
        assertEquals("string", message.get("type"));
        assertEquals(
            List.of("处理结果：{{ outputs.prepare.result }}"),
            message.get("examples")
        );
        assertTrue(((List<?>) schema.get("required")).contains("message"));
    }

    @Test
    void generatesTheConcreteTaskInputContractAndCachesIt() {
        DefaultPluginRegistry registry = registry(
            new TestNotificationTask()
        );
        JacksonMapper mapper = new JacksonMapper(
            new PluginModule(registry)
        );
        PluginSchemaGenerator generator = new PluginSchemaGenerator(mapper, registry);
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
        assertFalse(properties.containsKey("outputs"));
        assertFalse(properties.containsKey("route"));
        assertFalse(properties.containsKey("dependOn"));
        assertFalse(properties.containsKey("tasks"));

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
        DefaultPluginRegistry registry = registry(new Parallel());
        JacksonMapper mapper = new JacksonMapper(
            new PluginModule(registry)
        );
        PluginMetadata<?> metadata = registry.findMetadata(
            Parallel.class.getCanonicalName()
        ).orElseThrow();

        Map<String, Object> schema = new PluginSchemaGenerator(mapper, registry)
            .generate(metadata);
        Map<String, Object> concurrent = objectMap(
            objectMap(schema.get("properties")).get("concurrent")
        );
        assertTrue(objectMap(schema.get("properties")).containsKey("tasks"));

        assertEquals("integer", concurrent.get("type"));
        assertEquals(
            0,
            ((Number) concurrent.get("exclusiveMinimum")).intValue()
        );
        assertFalse(
            ((List<?>) schema.get("required")).contains("concurrent")
        );
    }

    @Test
    void routeSchemaExposesARequiredStringRoute() {
        DefaultPluginRegistry registry = registry(new Route());
        JacksonMapper mapper = new JacksonMapper(
            new PluginModule(registry)
        );
        PluginMetadata<?> metadata = registry.findMetadata(
            Route.class.getCanonicalName()
        ).orElseThrow();

        Map<String, Object> schema = new PluginSchemaGenerator(mapper, registry)
            .generate(metadata);
        Map<String, Object> properties = objectMap(
            schema.get("properties")
        );
        Map<String, Object> route = objectMap(properties.get("route"));
        List<?> required = (List<?>) schema.get("required");

        assertEquals("string", route.get("type"));
        assertEquals("flow-condition", route.get("format"));
        assertTrue(required.contains("route"));
        assertFalse(properties.containsKey("condition"));
    }

    /** 验证 Pause Schema 只公布 onPause/onResume，必填关系保持不变。 */
    @Test
    void pauseSchemaComesFromItsConcreteDefinitionFields() {
        DefaultPluginRegistry registry = registry(new Pause());
        JacksonMapper mapper = new JacksonMapper(
            new PluginModule(registry)
        );
        PluginMetadata<?> metadata = registry.findMetadata(
            Pause.class.getCanonicalName()
        ).orElseThrow();

        Map<String, Object> schema = new PluginSchemaGenerator(mapper, registry)
            .generate(metadata);
        Map<String, Object> properties = objectMap(
            schema.get("properties")
        );
        List<?> required = (List<?>) schema.get("required");

        assertEquals("暂停", metadata.title());
        assertTrue(properties.containsKey("onPause"));
        assertTrue(properties.containsKey("onResume"));
        assertFalse(properties.containsKey("pause"));
        assertFalse(properties.containsKey("resume"));
        assertTrue(properties.containsKey("duration"));
        assertTrue(properties.containsKey("behavior"));
        assertTrue(required.contains("onPause"));
        assertFalse(required.contains("onResume"));
        assertFalse(required.contains("duration"));
        assertFalse(required.contains("behavior"));
    }

    @Test
    void loopSchemasExposeTheirRequiredIterationContracts() {
        DefaultPluginRegistry registry = registry(
            new Loop(),
            new LoopUntil()
        );
        JacksonMapper mapper = new JacksonMapper(
            new PluginModule(registry)
        );
        PluginSchemaGenerator generator = new PluginSchemaGenerator(mapper, registry);

        Map<String, Object> loopSchema = generator.generate(
            registry.findMetadata(Loop.class.getCanonicalName())
                .orElseThrow()
        );
        Map<String, Object> loopProperties = objectMap(
            loopSchema.get("properties")
        );
        assertEquals(
            "integer",
            objectMap(loopProperties.get("times")).get("type")
        );
        assertTrue(((List<?>) loopSchema.get("required")).contains("times"));

        Map<String, Object> untilSchema = generator.generate(
            registry.findMetadata(LoopUntil.class.getCanonicalName())
                .orElseThrow()
        );
        Map<String, Object> untilProperties = objectMap(
            untilSchema.get("properties")
        );
        Map<String, Object> condition = objectMap(
            untilProperties.get("condition")
        );
        assertEquals("string", condition.get("type"));
        assertEquals("flow-condition", condition.get("format"));
        List<?> required = (List<?>) untilSchema.get("required");
        assertTrue(required.contains("condition"));
        assertTrue(required.contains("maxIterations"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static DefaultPluginRegistry registry(Plugin... plugins) {
        return new DefaultPluginRegistry(List.of(plugins));
    }
}
