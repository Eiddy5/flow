package org.cses.flow.controller.execution;

import org.paas.response.wrapper.annotation.SkipResponseWrap;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.exceptions.HttpStatusException;
import jakarta.inject.Inject;
import org.cses.flow.controller.flow.FlowModels.ExecutionView;
import org.cses.flow.controller.flow.FlowModels.RewindView;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.services.executions.ExecutionService;
import org.cses.flow.executor.commands.Create;
import org.pass.authx.security.session.AuthxSession;
import org.paas.session.bind.UserSession;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * User-facing HTTP adapter for Execution lifecycle operations.
 */
@SkipResponseWrap
@Controller("/executions")
public class ExecutionController {

    private ExecutionService executionService;

    @Inject
    public ExecutionController(ExecutionService executionService) {
        this.executionService = executionService;
    }

    /**
     * 为当前公司启动指定 Flow 的最新版本或精确版本，返回异步受理结果。
     * @param session 认证绑定且已选择公司的会话
     * @param key 非空的 Flow 业务键
     * @param version 指定版本；空 Optional 表示最新版本
     * @param body 可空请求体，其中 inputs 为输入映射
     * @return 包含启动命令的 HTTP 受理响应
     * @throws RuntimeException 未选择公司、输入无效或启动被拒绝时抛出
     */
    @Post("/flows/{key}/executions")
    public HttpResponse<Create> start(
            @UserSession AuthxSession session,
            String key,
            @QueryValue Optional<Long> version,
            @Body Map<String, Object> body
    ) {
        session.requireActiveCompanyId();
        Map<String, ?> inputs = bodyMap(body, "inputs");
        Create command = executionService.create(
                session,
                key,
                version,
                inputs
        );
        return HttpResponse.<Create>accepted().body(command);
    }

    /**
     * 查询当前公司的执行列表，按创建时间倒序返回。
     * @param session 认证绑定且已选择公司的会话
     * @return 当前公司可见的执行视图
     * @throws RuntimeException 未选择公司或请求处理失败时抛出
     */
    @Get("/executions")
    public List<ExecutionView> executions(
            @UserSession AuthxSession session
    ) {
        session.requireActiveCompanyId();
        return executionService.executions(session).stream()
                .sorted(
                        Comparator.comparingLong(
                                ExecutionController::createdAt
                        ).reversed()
                )
                .map(ExecutionView::from)
                .toList();
    }

    /**
     * 读取当前公司指定执行的视图。
     * @param session 认证绑定且已选择公司的会话
     * @param executionId 非空的执行 ID
     * @return 指定执行的视图
     * @throws RuntimeException 未选择公司或请求处理失败时抛出
     */
    @Get("/executions/{executionId}")
    public ExecutionView execution(
            @UserSession AuthxSession session,
            String executionId
    ) {
        session.requireActiveCompanyId();
        return ExecutionView.from(
                requireExecution(session, executionId)
        );
    }

    /**
     * 查询当前公司可见的执行派生树。
     * @param session 认证绑定且已选择公司的会话
     * @param executionId 派生树中已存在的执行 ID
     * @return 保留来源和任务归属信息的执行视图列表
     * @throws RuntimeException 未选择公司或执行不可访问时抛出
     */
    @Get("/executions/{executionId}/lineage")
    public List<ExecutionView> lineage(@UserSession AuthxSession session, String executionId) {
        session.requireActiveCompanyId();
        return executionService.lineage(session, executionId).stream().map(ExecutionView::from).toList();
    }

    /**
     * 提交当前公司指定执行的取消命令。
     * @param session 认证绑定且已选择公司的会话
     * @param executionId 非空的执行 ID
     * @return 受理时的执行视图
     * @throws RuntimeException 未选择公司或请求处理失败时抛出
     */
    @Post("/executions/{executionId}/cancel")
    public ExecutionView cancel(
            @UserSession AuthxSession session,
            String executionId
    ) {
        session.requireActiveCompanyId();
        return ExecutionView.from(
                executionService.cancel(session, executionId)
        );
    }

    /**
     * 提交指定暂停步骤的恢复命令。
     * @param session 认证绑定且已选择公司的会话
     * @param executionId 非空的执行 ID
     * @param taskRunId 待恢复步骤的运行 ID
     * @param body 可空请求体，其中 outputs 为恢复输出映射
     * @return 受理时的执行视图
     * @throws RuntimeException 未选择公司或请求处理失败时抛出
     */
    @Post(
            "/executions/{executionId}"
                    + "/task-runs/{taskRunId}/resume"
    )
    public ExecutionView resume(
            @UserSession AuthxSession session,
            String executionId,
            String taskRunId,
            @Body Map<String, Object> body
    ) {
        session.requireActiveCompanyId();
        Map<String, ?> outputs = bodyMap(body, "outputs");
        return ExecutionView.from(executionService.resume(
                session,
                executionId,
                taskRunId,
                outputs
        ));
    }

    /**
     * 提交当前执行从指定步骤退回目标步骤的命令。
     * @param session 认证绑定且已选择公司的会话
     * @param executionId 非空的执行 ID
     * @param taskRunId 来源步骤的运行 ID
     * @param body 包含 targetTaskRunId 和 reason 的非空请求体
     * @return 包含新执行身份与受影响步骤的受理视图
     * @throws RuntimeException 未选择公司或请求处理失败时抛出
     */
    @Post(
            "/executions/{executionId}"
                    + "/task-runs/{taskRunId}/rewind"
    )
    public RewindView rewind(
            @UserSession AuthxSession session,
            String executionId,
            String taskRunId,
            @Body Map<String, Object> body
    ) {
        session.requireActiveCompanyId();
        return RewindView.from(executionService.rewind(
                session,
                executionId,
                taskRunId,
                bodyText(body, "targetTaskRunId"),
                bodyText(body, "reason")
        ));
    }

    private Execution requireExecution(
            AuthxSession session,
            String executionId
    ) {
        return executionService.execution(session, executionId)
                .orElseThrow(() -> notFound(
                        "Execution does not exist: " + executionId
                ));
    }

    private static Map<String, ?> bodyMap(
            Map<String, Object> body,
            String field
    ) {
        if (body == null || body.get(field) == null) {
            return Map.of();
        }
        Object value = body.get(field);
        if (!(value instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException(
                    "Command field must be an object: " + field
            );
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(
                        "Command field keys must be strings: " + field
                );
            }
            map.put(key, entry.getValue());
        }
        return map;
    }

    private static String bodyText(
            Map<String, Object> body,
            String field
    ) {
        if (body == null || !(body.get(field) instanceof String value)
                || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Command field must be non-blank text: " + field
            );
        }
        return value.trim();
    }

    private static long createdAt(Execution execution) {
        return execution.state().history().getFirst().date();
    }

    private static HttpStatusException notFound(String message) {
        return new HttpStatusException(HttpStatus.NOT_FOUND, message);
    }
}
