package org.cses.flow.extensions.flow;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.plugins.DefaultPluginRegistry;
import org.cses.flow.core.serializers.JacksonMapper;
import org.cses.flow.core.plugins.PluginModule;
import org.cses.flow.core.serializers.PluginSchemaGenerator;
import org.cses.flow.infrastructure.repositories.flows.codec.TaskPropertiesCodec;
import org.cses.flow.core.plugins.TaskOutputs;
import org.cses.flow.core.plugins.TaskPluginTestSupport;
import org.cses.flow.infrastructure.repositories.executions.entries.ExecutionEntry;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 子调用身份与输出协议的技术边界验证，业务闭环由 UC13 覆盖。 */
class SubFlowTest {
    /** 子运行保留精确调用节点，复制和 Entry 往返均不丢失，跨租户和重复启动被拒绝。 */
    @Test
    void preservesInvocationAcrossCopyAndPersistence() {
        TaskPluginTestSupport.builtInContext();
        Session<User> session = session("subflow-company");
        Execution parent = Execution.create(null, session, "parent", 1, Map.of("value", "ok"));
        TaskRun caller = TaskRun.create("call", null, Map.of());
        parent.startWithTaskRuns(List.of(caller));
        assertThrows(IllegalArgumentException.class, () -> parent.startSubFlow(
                session("other-company"), caller.id(), "child", 2, Map.of()));
        assertEquals(State.Type.CREATED, parent.requireTaskRun(caller.id()).state().current());
        Execution child = parent.startSubFlow(session, caller.id(), "child", 2, Map.of("value", "ok"));
        assertEquals(parent.id(), child.origin().parentId());
        assertEquals(parent.id(), child.origin().originId());
        assertEquals(caller.id(), child.parentTaskRunId());
        assertEquals(Map.of("value", "ok"), parent.requireTaskRun(caller.id()).inputs());
        assertEquals(State.Type.RUNNING, parent.requireTaskRun(caller.id()).state().current());
        assertEquals(State.Type.CREATED, child.state().current());
        assertTrue(child.taskRuns().isEmpty());
        assertNull(child.lock());
        assertEquals(caller.id(), child.copy().parentTaskRunId());
        child.lock(0);
        Execution restored = ExecutionEntry.from(child).to(List.of());
        assertEquals(child.origin(), restored.origin());
        assertEquals(caller.id(), restored.parentTaskRunId());
        assertEquals(child.inputs(), restored.inputs());
        assertThrows(RuntimeException.class, () -> parent.startSubFlow(session, caller.id(), "child", 2, Map.of()));
    }

    /** 子流程明确归属独立执行协议，绑定调用参数并通过具体结果完成。 */
    @Test
    void declaresExecutableCapabilityAndReturnsChildResult() {
        TaskPluginTestSupport.builtInContext();
        SubFlow task = SubFlow.builder().id("call-id").key("call").flowKey("child").flowVersion(2L).build();
        assertInstanceOf(org.cses.flow.core.domains.tasks.ExecutableTask.class, task);
        assertFalse(task instanceof org.cses.flow.core.domains.tasks.OrchestrationTask<?>);
        assertFalse(task instanceof org.cses.flow.core.domains.tasks.RunnableTask<?>);
        var request = task.createExecution(Map.of());
        assertEquals("child", request.flowKey());
        assertEquals(2L, request.flowVersion());
        assertEquals(Map.of(), request.inputs());
        var output = task.completeExecution(org.cses.flow.core.runner.RunContext.builder()
                .variables(Map.of("execution", Map.of("id", "child-id"),
                    "outputs", Map.of("check", Map.of("result", "ok")))).build());
        assertEquals(Map.of("executionId", "child-id", "outputs", Map.of("check", Map.of("result", "ok"))),
                TaskOutputs.values(output));
    }

    /** 精确版本引用拒绝非法定义，具体 Output 的字段被现有泛型元数据发现。 */
    @Test
    void declaresConcreteOutputsAndValidatesReference() {
        var plugins = TaskPluginTestSupport.builtInContext();
        assertDoesNotThrow(() -> plugins.modelValidator().validate(SubFlow.builder()
                .id("call-id").key("call").flowKey("child").flowVersion(1L).build()));
        assertThrows(jakarta.validation.ConstraintViolationException.class,
                () -> plugins.modelValidator().validate(SubFlow.builder().id("call-id").key("call").build()));
        for (SubFlow invalid : List.of(
                SubFlow.builder().id("call-id").key("call").flowVersion(1L).build(),
                SubFlow.builder().id("call-id").key("call").flowKey("child").build(),
                SubFlow.builder().id("call-id").key("call").flowKey("child").flowVersion(0L).build(),
                SubFlow.builder().id("call-id").key("call").flowKey(" ").flowVersion(1L).build())) {
            assertThrows(jakarta.validation.ConstraintViolationException.class,
                    () -> plugins.modelValidator().validate(invalid));
        }
        assertEquals(Map.of("executionId", String.class, "outputs", Map.class), TaskOutputs.fields(SubFlow.class));
        assertEquals(Map.of("executionId", "child-id", "outputs", Map.of("wait", Map.of("approved", true))),
                TaskOutputs.values(SubFlow.Output.from("child-id", Map.of("wait", Map.of("approved", true)))));
    }

    /** 扁平引用字段在插件 Schema 和持久化中保持一致，不接受旧的嵌套配置。 */
    @Test
    void exposesAndPersistsFlatFlowFields() {
        TaskPluginTestSupport.builtInContext();
        var registry = new DefaultPluginRegistry(List.of(new SubFlow()));
        var mapper = new JacksonMapper(new PluginModule(registry));
        var schema = new PluginSchemaGenerator(mapper, registry)
                .generate(registry.findMetadata(SubFlow.class.getCanonicalName()).orElseThrow());
        var properties = (Map<?, ?>) schema.get("properties");
        assertTrue(properties.containsKey("flowKey"));
        assertTrue(properties.containsKey("flowVersion"));
        assertFalse(properties.containsKey("flow"));
        assertTrue(((List<?>) schema.get("required")).containsAll(List.of("flowKey", "flowVersion")));
        SubFlow task = SubFlow.builder().id("call-id").key("call")
                .flowKey("child").flowVersion(2L).build();
        var encoded = TaskPropertiesCodec.encode(task);
        assertEquals(java.util.Set.of("flowKey", "flowVersion"), encoded.asMap().keySet());
        SubFlow restored = (SubFlow) TaskPropertiesCodec.decode(encoded, task.id(),
                SubFlow.class.getCanonicalName(), task.key(), List.of(), List.of());
        assertEquals("child", restored.flowKey());
        assertEquals(2L, restored.flowVersion());
        assertEquals(task, restored);
        assertThrows(IllegalArgumentException.class, () -> TaskPropertiesCodec.decode(
                org.paas.json.JsonObject.FromMap(Map.of("flow", Map.of("key", "child", "version", 2))),
                task.id(), SubFlow.class.getCanonicalName(), task.key(), List.of(), List.of()));
    }

    /**
     * 创建仅供领域测试使用的可信租户会话。
     * @param companyId 非空租户编号
     * @return 独立会话
     */
    private static Session<User> session(String companyId) {
        User user = new User();
        user.setId("subflow-user");
        user.setCompanyId(companyId);
        Session<User> session = new Session<>();
        session.setCompanyId(companyId);
        session.setUser(user);
        return session;
    }
}
