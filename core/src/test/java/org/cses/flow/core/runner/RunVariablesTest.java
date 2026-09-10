package org.cses.flow.core.runner;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.expressions.TemplateExpression;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.TestOutputTasks;
import org.cses.flow.extensions.log.Log;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunVariablesTest {

    /** 检查根实例变量树和仅含直接父、最初实例两项的来源。 */
    @Test
    void buildsTheCanonicalRuntimeVariableTree() {
        Fixture fixture = fixture();

        Map<String, Object> variables = RunVariables.builder()
            .flow(fixture.flow())
            .execution(fixture.execution())
            .task(fixture.currentTask())
            .taskRun(fixture.currentTaskRun())
            .build();

        assertEquals(
            List.of(
                "flow",
                "task",
                "taskRun",
                "parents",
                "parent",
                "execution",
                "inputs",
                "outputs",
                "vars"
            ),
            List.copyOf(variables.keySet())
        );
        assertEquals(
            fixture.flow().key(),
            map(variables, "flow").get("key")
        );
        assertEquals(
            fixture.flow().id(),
            map(variables, "flow").get("id")
        );
        assertEquals(
            fixture.flow().reversion(),
            map(variables, "flow").get("version")
        );
        assertEquals(Map.of("orderId", "order-1"), variables.get("inputs"));
        assertEquals(Map.of("environment", "prod"), variables.get("vars"));
        assertEquals(
            Map.of("result", "second"),
            map(map(variables, "outputs"), "repeat")
        );
        assertEquals(
            fixture.currentTask().key(),
            map(variables, "task").get("key")
        );
        assertEquals(
            fixture.currentTaskRun().id(),
            map(variables, "taskRun").get("id")
        );
        assertEquals(
            fixture.execution().id(),
            map(variables, "execution").get("id")
        );
        assertEquals(Map.of(), map(map(variables, "execution"), "outputs"));
        Map<String, Object> origin = map(map(variables, "execution"), "origin");
        assertEquals(java.util.Set.of("parentId", "originId"), origin.keySet());
        org.junit.jupiter.api.Assertions.assertNull(origin.get("parentId"));
        assertEquals(fixture.execution().id(), origin.get("originId"));
        assertThrows(UnsupportedOperationException.class, () -> origin.put("parentId", "changed"));
    }

    /** 完成日志任务后省略其 VoidOutput，仍保留具体输出的数据与空业务结果。 */
    @Test
    void omitsVoidOutputsWithoutDiscardingConcreteEmptyResults() {
        Fixture fixture = fixture();
        fixture.execution().startTaskRun(fixture.rootTaskRun().id());
        fixture.execution().succeedTaskRun(fixture.rootTaskRun().id(), Map.of());

        Map<String, Object> variables = RunVariables.builder()
            .flow(fixture.flow())
            .execution(fixture.execution())
            .build();

        assertEquals(org.cses.flow.core.domains.flows.State.Type.SUCCESS,
            fixture.execution().requireTaskRun(fixture.rootTaskRun().id()).state().current());
        assertFalse(map(variables, "outputs").containsKey("root"));
        assertEquals(Map.of(
            "repeat", Map.of("result", "second"),
            "empty", Map.of()
        ), variables.get("outputs"));
    }

    /** 检查 replay 的表达式身份仍为当前实例，来源指向父实例且变量不可变。 */
    @Test
    void derivedExecutionVariablesKeepCurrentIdentityAndSourceRelationship() {
        Fixture fixture = fixture();
        Execution source = Execution.create(null, session(), fixture.flow().key(), 1L, Map.of("request", "A"));
        source.start();
        TaskRun target = source.createTaskRun(fixture.rootTask().id(), null, Map.of());
        source.startTaskRun(target.id());
        source.succeedTaskRun(target.id(), Map.of("token", "retained"));
        TaskRun pause = source.createTaskRun(fixture.currentTask().id(), null, Map.of());
        source.startTaskRun(pause.id());
        source.pauseTaskRun(pause.id());
        source.pause();
        Execution derived = source.replay(org.paas.common.util.StringUtil.newId(), session(),
            pause.id(), target.id(), "redo", List.of(pause.id(), target.id()));
        Map<String, Object> variables = RunVariables.builder().flow(fixture.flow()).execution(derived).build();
        assertEquals(derived.id(), map(variables, "execution").get("id"));
        assertEquals(Map.of("parentId", source.id(), "originId", source.id()),
            map(map(variables, "execution"), "origin"));
        assertEquals("RUNNING", map(variables, "execution").get("state"));
        assertThrows(UnsupportedOperationException.class,
            () -> map(map(variables, "execution"), "origin").put("originId", "changed"));
        assertFalse(containsDomainObject(variables));
    }

    @Test
    void exposesDirectParentAndNearestFirstAncestorChain() {
        Fixture fixture = fixture();

        Map<String, Object> variables = RunVariables.builder()
            .flow(fixture.flow())
            .execution(fixture.execution())
            .task(fixture.currentTask())
            .taskRun(fixture.currentTaskRun())
            .build();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parents =
            (List<Map<String, Object>>) variables.get("parents");
        assertEquals(2, parents.size());
        assertEquals(
            fixture.parentTaskRun().id(),
            map(parents.get(0), "taskRun").get("id")
        );
        assertEquals(
            fixture.rootTaskRun().id(),
            map(parents.get(1), "taskRun").get("id")
        );
        assertEquals(parents.getFirst(), variables.get("parent"));
    }

    @Test
    void derivesAnEmptyParentChainForARootTaskRun() {
        Fixture fixture = fixture();

        Map<String, Object> variables = RunVariables.builder()
            .flow(fixture.flow())
            .execution(fixture.execution())
            .task(fixture.rootTask())
            .taskRun(fixture.rootTaskRun())
            .build();

        assertEquals(List.of(), variables.get("parents"));
        assertFalse(variables.containsKey("parent"));
    }

    @Test
    void omitsNullableFlowMetadata() {
        Flow draft = Flow.create(
            session(),
            "draft-flow",
            "",
            Map.of(),
            List.of(),
            List.of(),
            "source"
        );

        Map<String, Object> variables = RunVariables.builder()
            .flow(draft)
            .build();

        assertFalse(map(variables, "flow").containsKey("version"));
    }

    @Test
    void createsADeeplyImmutableSnapshotWithoutLeakingDomainObjects() {
        Fixture fixture = fixture();
        Map<String, Object> variables = RunVariables.builder()
            .flow(fixture.flow())
            .execution(fixture.execution())
            .task(fixture.currentTask())
            .taskRun(fixture.currentTaskRun())
            .build();

        assertThrows(
            UnsupportedOperationException.class,
            () -> variables.put("custom", true)
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> map(variables, "inputs").put("orderId", "changed")
        );
        @SuppressWarnings("unchecked")
        List<Object> parents = (List<Object>) variables.get("parents");
        assertThrows(
            UnsupportedOperationException.class,
            () -> parents.add(Map.of())
        );
        assertFalse(containsDomainObject(variables));
    }

    /**
     * 创建带正版本流程及运行记录，包含具体任务的非空与空成功结果。
     *
     * @return 相互对应的独立流程、运行实例与任务样本
     */
    private static Fixture fixture() {
        Session<User> session = session();
        Log root = task("root-id", "root");
        Task repeated = TestOutputTasks.Result.builder().id("repeat-id").key("repeat").build();
        Task empty = TestOutputTasks.Result.builder().id("empty-id").key("empty").build();
        Log parent = task("parent-id", "parent");
        Log current = task("current-id", "current");
        Flow transientFlow = Flow.deploy(
            session,
            "run-variables-flow",
            "",
            Map.of("environment", "prod"),
            List.of(),
            List.of(),
            List.of(root, repeated, empty, parent, current),
            "source",
            null
        );
        Flow flow = Flow.rehydrate(
            transientFlow.id(),
            transientFlow.companyId(),
            transientFlow.key(),
            false,
            1L,
            transientFlow.description(),
            transientFlow.variables(),
            transientFlow.inputs(),
            transientFlow.outputs(),
            transientFlow.tasks(),
            transientFlow.status(),
            transientFlow.creator(),
            transientFlow.updater(),
            null,
            transientFlow.createdAt(),
            transientFlow.updatedAt(),
            null,
            transientFlow.source()
        );
        Execution execution = Execution.create(
            "execution-1",
            session,
            flow.key(),
            flow.reversion(),
            Map.of("orderId", "order-1")
        );
        TaskRun rootRun = TaskRun.create(root.id(), null, Map.of());
        TaskRun first = TaskRun.create(
            repeated.id(),
            rootRun.id(),
            Map.of(),
            1
        );
        TaskRun second = TaskRun.create(
            repeated.id(),
            rootRun.id(),
            Map.of(),
            2
        );
        TaskRun emptyRun = TaskRun.create(empty.id(), rootRun.id(), Map.of());
        TaskRun parentRun = TaskRun.create(
            parent.id(),
            rootRun.id(),
            Map.of("from", "root")
        );
        TaskRun currentRun = TaskRun.create(
            current.id(),
            parentRun.id(),
            Map.of("payload", "value")
        );
        execution.startWithTaskRuns(List.of(
            rootRun,
            first,
            second,
            emptyRun,
            parentRun,
            currentRun
        ));
        execution.startTaskRun(first.id());
        execution.succeedTaskRun(first.id(), Map.of("result", "first"));
        execution.startTaskRun(second.id());
        execution.succeedTaskRun(second.id(), Map.of("result", "second"));
        execution.startTaskRun(emptyRun.id());
        execution.succeedTaskRun(emptyRun.id(), Map.of());
        return Fixture.from(
            flow,
            execution,
            current,
            execution.requireTaskRun(currentRun.id()),
            execution.requireTaskRun(parentRun.id()),
            execution.requireTaskRun(rootRun.id()),
            root
        );
    }

    private static Log task(String id, String key) {
        return Log.builder()
            .id(id)
            .key(key)
            .message(TemplateExpression.parse("test step"))
            .build();
    }

    private static Session<User> session() {
        User user = new User();
        user.setId("run-variables-user");
        user.setName("Run Variables User");
        user.setCompanyId("run-variables-company");
        Session<User> session = new Session<>();
        session.setCompanyId("run-variables-company");
        session.setUser(user);
        return session;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(
        Map<String, ?> source,
        String key
    ) {
        return (Map<String, Object>) source.get(key);
    }

    private static boolean containsDomainObject(Object value) {
        if (value instanceof Flow
            || value instanceof Execution
            || value instanceof TaskRun
            || value instanceof Task) {
            return true;
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream()
                .anyMatch(RunVariablesTest::containsDomainObject);
        }
        if (value instanceof List<?> list) {
            return list.stream()
                .anyMatch(RunVariablesTest::containsDomainObject);
        }
        return false;
    }

    private record Fixture(
        Flow flow,
        Execution execution,
        Log currentTask,
        TaskRun currentTaskRun,
        TaskRun parentTaskRun,
        TaskRun rootTaskRun,
        Log rootTask
    ) {
        private static Fixture from(
            Flow flow,
            Execution execution,
            Log currentTask,
            TaskRun currentTaskRun,
            TaskRun parentTaskRun,
            TaskRun rootTaskRun,
            Log rootTask
        ) {
            return new Fixture(
                flow,
                execution,
                currentTask,
                currentTaskRun,
                parentTaskRun,
                rootTaskRun,
                rootTask
            );
        }
    }
}
