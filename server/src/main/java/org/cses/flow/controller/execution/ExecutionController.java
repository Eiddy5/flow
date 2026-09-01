package org.cses.flow.controller.execution;

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
import org.cses.flow.controller.ApiModels.ErrorView;
import org.cses.flow.controller.flow.FlowModels.ExecutionView;
import org.cses.flow.controller.flow.FlowModels.RewindView;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.services.executions.ExecutionService;
import org.cses.flow.executor.commands.Create;
import org.paas.session.Session;
import org.paas.session.User;
import org.paas.session.bind.UserSession;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * User-facing HTTP adapter for Execution lifecycle operations.
 */
@Controller("/executions")
public class ExecutionController {

    private final ExecutionService executionService;

    @Inject
    public ExecutionController(ExecutionService executionService) {
        this.executionService = executionService;
    }

    /**
     * 启动一个 Execution。
     *
     * <p>{@code key} 用于标识要启动的逻辑 Flow；{@code version} 为空时
     * 使用该 Flow 的最新版本，不为空时使用指定版本。</p>
     *
     * @param key Flow 的业务标识
     * @param version 要启动的 Flow 版本；为空表示使用最新版本
     */
    @Post("/flows/{key}/executions")
    public HttpResponse<Create> start(
            @UserSession Session<User> session,
            String key,
            @QueryValue Optional<Long> version,
            @Body Map<String, Object> body
    ) {
        Map<String, ?> inputs = bodyMap(body, "inputs");
        Create command = executionService.create(
                session,
                key,
                version,
                inputs
        );
        return HttpResponse.<Create>accepted().body(command);
    }

    @Get("/executions")
    public List<ExecutionView> executions(
            @UserSession Session<User> session
    ) {
        return executionService.executions(session).stream()
                .sorted(
                        Comparator.comparingLong(
                                ExecutionController::createdAt
                        ).reversed()
                )
                .map(ExecutionView::from)
                .toList();
    }

    @Get("/executions/{executionId}")
    public ExecutionView execution(
            @UserSession Session<User> session,
            String executionId
    ) {
        return ExecutionView.from(
                requireExecution(session, executionId)
        );
    }

    @Post("/executions/{executionId}/cancel")
    public ExecutionView cancel(
            @UserSession Session<User> session,
            String executionId
    ) {
        return ExecutionView.from(
                executionService.cancel(session, executionId)
        );
    }

    @Post(
            "/executions/{executionId}"
                    + "/task-runs/{taskRunId}/resume"
    )
    public ExecutionView resume(
            @UserSession Session<User> session,
            String executionId,
            String taskRunId,
            @Body Map<String, Object> body
    ) {
        Map<String, ?> outputs = bodyMap(body, "outputs");
        return ExecutionView.from(executionService.resume(
                session,
                executionId,
                taskRunId,
                outputs
        ));
    }

    @Post(
            "/executions/{executionId}"
                    + "/task-runs/{taskRunId}/rewind"
    )
    public RewindView rewind(
            @UserSession Session<User> session,
            String executionId,
            String taskRunId,
            @Body Map<String, Object> body
    ) {
        return RewindView.from(executionService.rewind(
                session,
                executionId,
                taskRunId,
                bodyText(body, "targetTaskRunId"),
                bodyText(body, "reason")
        ));
    }

    @Error(exception = WorkflowException.class)
    public HttpResponse<ErrorView> workflowError(
            WorkflowException exception
    ) {
        return HttpResponse.status(HttpStatus.CONFLICT)
                .body(new ErrorView(exception.getMessage()));
    }

    @Error(exception = IllegalArgumentException.class)
    public HttpResponse<ErrorView> invalidArgument(
            IllegalArgumentException exception
    ) {
        return HttpResponse.badRequest(
                new ErrorView(exception.getMessage())
        );
    }

    private Execution requireExecution(
            Session<User> session,
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
