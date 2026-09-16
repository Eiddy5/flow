package org.cses.flow.scheduler;

import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.triggers.Trigger;
import org.cses.flow.scheduler.events.SchedulerEvent;
import org.cses.flow.core.plugins.DefaultPluginRegistry;
import org.cses.flow.core.plugins.PluginModule;
import org.cses.flow.core.serializers.JacksonMapper;
import org.cses.flow.core.serializers.YamlParser;
import org.cses.flow.core.serializers.PluginSchemaGenerator;
import org.cses.flow.extensions.log.Log;
import org.cses.flow.extensions.triggers.ScheduleTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.cses.flow.infrastructure.queues.pulsar.PulsarTestEnvironment;
import org.paas.json.JsonObject;
import org.paas.common.util.StringUtil;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 触发器多态与配置边界技术验证，不替代 UC 自动启动证据。 */
class TriggerDefinitionTest {
    private JacksonMapper mapper;
    private DefaultPluginRegistry registry;

    /** 初始化真实插件注册和 YAML/JSON 绑定链路。 */
    @BeforeEach
    void initialize() {
        registry = new DefaultPluginRegistry(List.of(new Log(), new ScheduleTrigger()));
        mapper = new JacksonMapper(new PluginModule(registry));
    }

    /** YAML 首次生成两个不同身份，JSON 往返沿用身份与时间配置。 */
    @Test
    void sourceAllocatesIdsAndPersistenceRetainsThem() {
        Flow flow = YamlParser.parse(SchedulerTestSupport.yaml("trigger-definition", 10_000, 20_000), Flow.class);
        assertEquals(2, flow.triggers().size());
        Trigger first = flow.triggers().getFirst();
        assertFalse(first.id().isBlank());
        assertNotEquals(first.id(), flow.triggers().get(1).id());
        var restored = mapper.convertValue(mapper.toMap(first), Trigger.class);
        assertEquals(first.id(), restored.id());
        assertEquals(first.key(), restored.key());
        assertEquals(first.inputs(), restored.inputs());
        assertEquals(((ScheduleTrigger) first).cron(), ((ScheduleTrigger) restored).cron());
        assertEquals("Asia/Shanghai", ((ScheduleTrigger) restored).timezone());
    }

    /** 源码不能注入系统身份，也不能将普通 Task 当作 Trigger。 */
    @Test
    void sourceRejectsCallerIdentityAndWrongPluginCapability() {
        String yaml = SchedulerTestSupport.yaml("trigger-invalid", 10_000, 20_000);
        assertThrows(IllegalArgumentException.class, () -> YamlParser.parse(
            yaml.replace("- key: first\n", "- key: first\n    id: caller-id\n"), Flow.class));
        assertThrows(IllegalArgumentException.class, () -> YamlParser.parse(
            yaml.replace("org.cses.flow.extensions.triggers.ScheduleTrigger", Log.class.getCanonicalName()), Flow.class));
    }

    /** 非法 Cron、时区和空输入值在配置校验时拒绝。 */
    @Test
    void rejectsInvalidScheduleConfiguration() {
        String id = StringUtil.newId();
        var trigger = ScheduleTrigger.builder().id(id).key("schedule")
            .cron("0 0 9 * * *").timezone("Asia/Shanghai").inputs(Map.of()).build();
        assertDoesNotThrow(trigger::validate);
        assertThrows(RuntimeException.class, () -> ScheduleTrigger.builder().id(id).key("schedule")
            .cron("invalid").timezone("Asia/Shanghai").build().validate());
        assertThrows(RuntimeException.class, () -> ScheduleTrigger.builder().id(id).key("schedule")
            .cron("0 0 9 * * *").timezone("Invalid/Zone").build().validate());
        assertThrows(RuntimeException.class, () -> ScheduleTrigger.builder().id(id).key("schedule")
            .cron("0 0 9 * * *").timezone("Asia/Shanghai").inputs(java.util.Collections.singletonMap("input", null))
            .build().validate());
    }

    /** 插件目录能发现定时类型，定义 Schema 提供业务配置并隐藏系统身份。 */
    @Test
    void discoversTriggerAndExposesConfigurationSchemaWithoutIdentity() {
        var metadata = registry.findMetadata(ScheduleTrigger.class.getCanonicalName()).orElseThrow();
        var schema = new PluginSchemaGenerator(mapper, registry).generate(metadata);
        var properties = (Map<?, ?>) schema.get("properties");
        assertNotNull(properties);
        assertTrue(properties.keySet().containsAll(List.of("key", "cron", "timezone", "inputs", "disabled")));
        assertFalse(properties.containsKey("id"));
        assertTrue(registry.plugins().stream().anyMatch(group -> group.triggers().stream()
            .anyMatch(trigger -> trigger.typeName().equals(ScheduleTrigger.class.getCanonicalName()))));
    }

    /** PAAS 会省略空 Map，配置事件和空输入命中事件仍须完成持久化往返。 */
    @Test
    void emptyEventInputsSurvivePaasJsonRoundTrip() {
        PulsarTestEnvironment.initializeJson();
        var changed = SchedulerEvent.from("FLOW_CHANGED", "test-company", StringUtil.newId(), "flow", 2,
            null, null, null, "test-actor", Map.of());
        var fired = SchedulerEvent.from("TRIGGER_FIRED", "test-company", StringUtil.newId(), "flow", 2,
            "schedule", 1_000L, StringUtil.newId(), "test-actor", Map.of());
        for (var event : List.of(changed, fired)) {
            var restored = JsonObject.Parse(JsonObject.From(event).toJson()).asObject(SchedulerEvent.class);
            assertEquals(event, restored);
            assertTrue(restored.inputs().isEmpty());
        }
    }
}
