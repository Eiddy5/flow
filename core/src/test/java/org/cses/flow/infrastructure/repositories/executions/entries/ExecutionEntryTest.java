package org.cses.flow.infrastructure.repositories.executions.entries;

import io.micronaut.json.JsonMapper;
import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.Generation;
import org.cses.flow.core.domains.executions.Origin;
import org.cses.flow.core.domains.flows.State;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonFactory;
import org.paas.json.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutionEntryTest {

    @BeforeAll
    static void initializeJsonMapper() {
        JsonFactory.instance = JsonMapper.createDefault();
    }

    /** 采用完整重建签名，验证根来源、状态历史和输入通过 Entry 边界后保持。 */
    @Test
    void mapsTheCompleteStateValueThroughTheEntryBoundary() {
        State state = State.rehydrate(
            State.Type.RUNNING,
            List.of(
                State.History.rehydrate(State.Type.CREATED, 100L),
                State.History.rehydrate(State.Type.RUNNING, 200L)
            )
        );
        Execution execution = Execution.rehydrate(
            "execution-1",
            "company-1",
            ActorRef.create("actor-1", "Flow User"),
            100L,
            "flow-1",
            1,
            Map.of("amount", 1200),
            Generation.empty(), state,
            List.of(), Origin.create(null, "execution-1"), List.of()
        );

        ExecutionEntry entry = ExecutionEntry.from(execution);
        JsonObject storedState = JsonObject.Parse(entry.state.data());

        assertEquals(
            Set.of("current", "history"),
            storedState.asMap().keySet()
        );
        Execution restored = entry.to(List.of());
        assertEquals(state, restored.state());
        assertEquals(execution.inputs(), restored.inputs());
    }
    /** 验证快照 JSON 数组同时往返 null 数值和真实循环/退回坐标，并保持状态隔离。 */
    @Test
    void roundTripsInheritedSnapshotsWithNullableAndPresentCoordinates() {
        org.paas.session.User user = new org.paas.session.User();
        user.setId("codec-user");
        user.setCompanyId("codec-company");
        org.paas.session.Session<org.paas.session.User> session = new org.paas.session.Session<>();
        session.setCompanyId("codec-company");
        session.setUser(user);
        Execution source = Execution.create(null, session, "codec-flow", 1, Map.of("request", "original"));
        source.start();
        var target = source.createTaskRun("target", null, Map.of("nested", Map.of("amount", 3)));
        source.startTaskRun(target.id());
        source.succeedTaskRun(target.id(), Map.of("value", "original"));
        var pause = source.createTaskRun("pause", null, Map.of());
        source.startTaskRun(pause.id());
        source.pauseTaskRun(pause.id());
        var sibling = source.createTaskRun("sibling", null, Map.of());
        source.startTaskRun(sibling.id());
        source.pauseTaskRun(sibling.id());
        source.pause();
        Execution derived = source.replay(org.paas.common.util.StringUtil.newId(), session,
            pause.id(), target.id(), "redo", List.of(pause.id(), target.id()));
        var restored = ExecutionEntry.from(derived).to(List.of());
        assertEquals(derived.origin(), restored.origin());
        assertEquals(derived.inputs(), restored.inputs());
        assertEquals(derived.generation().current(), restored.generation().current());
        assertEquals(derived.inheritedTaskRuns().stream().map(run -> run.id()).toList(),
            restored.inheritedTaskRuns().stream().map(run -> run.id()).toList());
        org.junit.jupiter.api.Assertions.assertTrue(restored.ownTaskRuns().isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(restored.requireTaskRun(target.id()).iteration().isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(restored.requireTaskRun(target.id()).executionGenerationVersion().isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(restored.requireTaskRun(target.id()).parentId().isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(restored.requireTaskRun(target.id()).error().isEmpty());
        assertEquals(target.inputs(), restored.requireTaskRun(target.id()).inputs());
        assertEquals(target.outputs(), restored.requireTaskRun(target.id()).outputs());
        restored.resumeTaskRun(sibling.id(), Map.of("decision", "NEW"));
        assertEquals(State.Type.PAUSED, derived.requireTaskRun(sibling.id()).state().current());
        assertEquals(State.Type.KILLED, source.requireTaskRun(sibling.id()).state().current());
        assertEquals(Map.of(), source.requireTaskRun(sibling.id()).outputs());

        var coordinates = org.cses.flow.core.domains.executions.TaskRun.create(
            "loop-child", "parent", Map.of("value", "input"), 2, 3);
        var codec = org.cses.flow.infrastructure.repositories.executions.codec.TaskRunSnapshotsJsonCodec.encode(List.of(coordinates));
        var decoded = org.cses.flow.infrastructure.repositories.executions.codec.TaskRunSnapshotsJsonCodec.decode(codec).getFirst();
        assertEquals(2, decoded.iteration().orElseThrow());
        assertEquals(3, decoded.executionGenerationVersion().orElseThrow());
        assertEquals(coordinates.id(), decoded.id());
        assertEquals("parent", decoded.parentId().orElseThrow());
        assertEquals(coordinates.inputs(), decoded.inputs());
        assertEquals(coordinates.state(), decoded.state());
    }

    /** 非空错误按原字符串往返，错误类型不能被转成字符串后混入运行快照。 */
    @Test
    void preservesRealErrorsAndRejectsNonStringSnapshotFields() {
        var failed = org.cses.flow.core.domains.executions.TaskRun.rehydrate(
            "failed-run", "failed-task", null, null, null, Map.of(), Generation.empty(),
            State.rehydrate(State.Type.FAILED, List.of(
                State.History.rehydrate(State.Type.CREATED, 1),
                State.History.rehydrate(State.Type.RUNNING, 2),
                State.History.rehydrate(State.Type.FAILED, 3))),
            Map.of(), "real failure");
        var encoded = org.cses.flow.infrastructure.repositories.executions.codec.TaskRunSnapshotsJsonCodec.encode(List.of(failed));
        var parsed = org.paas.json.JsonObjects.Parse(encoded.toJson());
        var restored = org.cses.flow.infrastructure.repositories.executions.codec.TaskRunSnapshotsJsonCodec.decode(parsed).getFirst();
        assertEquals("real failure", restored.error().orElseThrow());
        for (String field : List.of("id", "taskId", "parentId", "error")) {
            var invalid = org.paas.json.JsonObjects.Create();
            invalid.add(JsonObject.Parse(encoded.getObject(0).toJson()).put(field, 42));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> org.cses.flow.infrastructure.repositories.executions.codec.TaskRunSnapshotsJsonCodec.decode(invalid));
        }
    }
}
