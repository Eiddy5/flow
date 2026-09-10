package org.cses.flow.extensions.flow;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.tasks.OrchestrationTask.FlowReference;
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

    /** 精确版本引用拒绝非法定义，具体 Output 的字段被现有泛型元数据发现。 */
    @Test
    void declaresConcreteOutputsAndValidatesReference() {
        var plugins = TaskPluginTestSupport.builtInContext();
        assertDoesNotThrow(() -> plugins.modelValidator().validate(SubFlow.builder()
                .id("call-id").key("call").flow(FlowReference.from("child", 1)).build()));
        assertThrows(jakarta.validation.ConstraintViolationException.class,
                () -> plugins.modelValidator().validate(SubFlow.builder().id("call-id").key("call").build()));
        assertThrows(IllegalArgumentException.class, () -> FlowReference.from("child", 0));
        assertThrows(IllegalArgumentException.class, () -> FlowReference.from(" ", 1));
        assertEquals(Map.of("executionId", String.class, "outputs", Map.class), TaskOutputs.fields(SubFlow.class));
        assertEquals(Map.of("executionId", "child-id", "outputs", Map.of("wait", Map.of("approved", true))),
                TaskOutputs.values(SubFlow.Output.from("child-id", Map.of("wait", Map.of("approved", true)))));
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
