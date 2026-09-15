package org.cses.flow.controller.execution;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import org.cses.flow.controller.flow.FlowModels.ExecutionView;
import org.cses.flow.controller.flow.FlowModels.RewindView;
import org.cses.flow.core.services.executions.ExecutionService;
import org.cses.flow.core.services.executions.RewindResult;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import io.micronaut.json.JsonMapper;
import org.paas.json.JsonFactory;
import org.paas.json.JsonObject;
import org.paas.session.SessionUser;
import org.pass.authx.security.session.AuthxSession;
import org.pass.authx.security.session.AuthxUser;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ExecutionControllerTest {

    @Test
    void ownsOnlyTheExecutionServiceAtTheExecutionHttpSeam() {
        Controller controller = ExecutionController.class.getAnnotation(
            Controller.class
        );
        java.lang.reflect.Field[] fields = ExecutionController.class
            .getDeclaredFields();

        assertEquals("/executions", controller.value());
        assertEquals(1, fields.length);
        assertTrue(Arrays.stream(fields)
            .allMatch(field -> field.getType() == ExecutionService.class));
    }

    @Test
    void preservesTheExecutionRoutesAfterTheControllerSplit()
        throws NoSuchMethodException {
        Method start = ExecutionController.class.getDeclaredMethod(
            "start",
            AuthxSession.class,
            String.class,
            Optional.class,
            Map.class
        );
        Method executions = ExecutionController.class.getDeclaredMethod(
            "executions",
            AuthxSession.class
        );
        Method rewind = ExecutionController.class.getDeclaredMethod(
            "rewind",
            AuthxSession.class,
            String.class,
            String.class,
            Map.class
        );

        assertEquals(
            "/flows/{key}/executions",
            start.getAnnotation(Post.class).value()
        );
        assertEquals(
            "/executions",
            executions.getAnnotation(Get.class).value()
        );
        assertEquals(
            "/executions/{executionId}/task-runs/{taskRunId}/rewind",
            rewind.getAnnotation(Post.class).value()
        );
        assertEquals(RewindView.class, rewind.getReturnType());
    }

    @Test
    void exposesTheExecutionViewFromTheExecutionControllerPackage()
        throws NoSuchMethodException {
        Method endpoint = ExecutionController.class.getDeclaredMethod(
            "execution",
            AuthxSession.class,
            String.class
        );

        assertEquals(ExecutionView.class, endpoint.getReturnType());
    }

    /** 核对现有 Controller 前缀与新 lineage 路由的真实组合，不推断 /api 前缀。 */
    @Test
    void declaresTheCompleteLineageAndRewindRoutes() throws NoSuchMethodException {
        String prefix = ExecutionController.class.getAnnotation(Controller.class).value();
        Method lineage = ExecutionController.class.getDeclaredMethod("lineage", AuthxSession.class, String.class);
        Method rewind = ExecutionController.class.getDeclaredMethod("rewind", AuthxSession.class, String.class,
            String.class, Map.class);
        assertEquals("/executions/executions/{executionId}/lineage",
            prefix + lineage.getAnnotation(Get.class).value());
        assertEquals("/executions/executions/{executionId}/task-runs/{taskRunId}/rewind",
            prefix + rewind.getAnnotation(Post.class).value());
        assertEquals("java.util.List<org.cses.flow.controller.flow.FlowModels$ExecutionView>",
            lineage.getGenericReturnType().getTypeName());
    }

    /** 方法与 PAAS JSON 边界测试；Service 替身只隔离适配器，不作为真实 HTTP 或运行验收。 */
    @Test
    void serializesOriginMembershipAndAcceptedRewindThroughAdapterMethods() {
        JsonFactory.instance = JsonMapper.createDefault();
        AuthxUser user = new AuthxUser();
        user.setId("view-user");
        user.setCompanyId("view-company");
        AuthxSession session = new AuthxSession();
        session.setCompanyId("view-company");
        session.setUser(user);
        Execution source = Execution.create("view-root", session, "view-flow", 1, Map.of());
        source.start();
        TaskRun target = source.createTaskRun("target", null, Map.of());
        source.startTaskRun(target.id());
        source.succeedTaskRun(target.id(), Map.of("token", "original"));
        TaskRun pause = source.createTaskRun("approval", null, Map.of());
        source.startTaskRun(pause.id());
        source.pauseTaskRun(pause.id());
        source.pause();
        List<String> affected = List.of(pause.id(), target.id());
        RewindResult accepted = RewindResult.from("view-derived", source, affected);
        Execution derived = source.replay("view-derived", session, pause.id(), target.id(), "redo", affected);
        TaskRun fresh = TaskRun.create("target", null, Map.of(), null, 1);
        derived.addTaskRuns(List.of(fresh));
        ExecutionController controller = new ExecutionController(new ExecutionService(null, null, null) {
            /** 返回受理时的独立原快照，验证请求字段准确交给 Service。 */
            @Override
            public <S extends Session<U>, U extends SessionUser> RewindResult rewind(S caller, String id,
                    String sourceTaskRunId, String targetTaskRunId, String reason) {
                assertSame(session, caller);
                assertEquals(source.id(), id);
                assertEquals(pause.id(), sourceTaskRunId);
                assertEquals(target.id(), targetTaskRunId);
                assertEquals("redo", reason);
                return accepted;
            }

            /** Service 租户隔离由 Core 真实集成测试验证，此处只验证传参和映射。 */
            @Override
            public <S extends Session<U>, U extends SessionUser> List<Execution> lineage(S caller, String id) {
                assertSame(session, caller);
                assertEquals(derived.id(), id);
                return List.of(source, derived);
            }
        });
        RewindView receipt = controller.rewind(session, source.id(), pause.id(),
            Map.of("targetTaskRunId", target.id(), "reason", " redo "));
        JsonObject receiptJson = JsonObject.Parse(JsonObject.From(receipt).toJson());
        assertEquals(Set.of("executionId", "sourceExecution", "affectedTaskRunIds"), receiptJson.asMap().keySet());
        assertEquals(derived.id(), receiptJson.getString("executionId"));
        assertEquals(affected, receipt.getAffectedTaskRunIds());
        assertEquals("PAUSED", receipt.getSourceExecution().getState());
        assertEquals(source.id(), receipt.getSourceExecution().getId());
        assertEquals("PAUSED", JsonObject.FromMap((Map) receiptJson.asMap().get("sourceExecution")).getString("state"));

        List<ExecutionView> lineage = controller.lineage(session, derived.id());
        assertEquals(List.of(source.id(), derived.id()), lineage.stream().map(ExecutionView::getId).toList());
        assertEquals("KILLED", lineage.getFirst().getState());
        JsonObject rootOrigin = JsonObject.Parse(JsonObject.From(lineage.getFirst().getOrigin()).toJson());
        assertEquals(Set.of("parentId", "originId"), rootOrigin.asMap().keySet());
        assertNull(rootOrigin.asMap().get("parentId"));
        assertEquals(source.id(), rootOrigin.getString("originId"));
        JsonObject derivedJson = JsonObject.Parse(JsonObject.From(lineage.getLast()).toJson());
        assertTrue(derivedJson.asMap().keySet().containsAll(Set.of("origin", "inheritedTaskRunIds", "effectiveTaskRunIds")));
        assertEquals(source.id(), lineage.getLast().getOrigin().getParentId());
        assertEquals(source.id(), lineage.getLast().getOrigin().getOriginId());
        assertEquals(source.taskRuns().stream().map(TaskRun::id).toList(), lineage.getLast().getInheritedTaskRunIds());
        assertEquals(List.of(fresh.id()), lineage.getLast().getEffectiveTaskRunIds());
        assertEquals(derived.taskRuns().stream().map(TaskRun::id).toList(),
            lineage.getLast().getTaskRuns().stream().map(view -> view.getId()).toList());
    }
}
