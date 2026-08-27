package org.cses.flow.controller.flow;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.exceptions.HttpStatusException;
import jakarta.inject.Inject;
import org.cses.flow.controller.flow.FlowModels.DefinitionView;
import org.cses.flow.controller.flow.FlowModels.DraftView;
import org.cses.flow.controller.flow.FlowModels.ErrorView;
import org.cses.flow.controller.flow.FlowModels.ExecutionView;
import org.cses.flow.controller.flow.FlowModels.FlowView;
import org.cses.flow.controller.flow.FlowModels.InputTypeView;
import org.cses.flow.controller.flow.FlowModels.SessionView;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.serializers.FlowDefinitionSerializer;
import org.cses.flow.core.serializers.YamlParser;
import org.cses.flow.core.services.executions.ExecutionService;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
import org.cses.flow.core.services.flows.FlowService;
import org.cses.flow.executor.commands.Create;
import org.paas.session.Session;
import org.paas.session.User;
import org.paas.session.bind.UserSession;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * User-facing HTTP adapter for the authenticated Flow management surface.
 */
@Controller("/api")
public class FlowController {

    private FlowService flowService;
    private ExecutionService executionService;
    private FlowDefinitionSerializer flowDefinitionSerializer;

    @Inject
    public FlowController(
            FlowService flowService,
            ExecutionService executionService,
            FlowDefinitionSerializer flowDefinitionSerializer
    ) {
        this.flowService = flowService;
        this.executionService = executionService;
        this.flowDefinitionSerializer = flowDefinitionSerializer;
    }

    @Get("/session")
    public SessionView session(
            @UserSession Session<User> session
    ) {
        return SessionView.from(session);
    }

    @Get("/data-types")
    public List<InputTypeView> dataTypes(
            @UserSession Session<User> session
    ) {
        return InputTypeView.catalog();
    }

    @Get("/flows")
    public List<DraftView> drafts(
            @UserSession Session<User> session
    ) {
        return flowService.drafts(session).stream()
                .map(draft -> draftView(session, draft))
                .toList();
    }

    @Post("/flows")
    public HttpResponse<DraftView> saveDraft(
            @UserSession Session<User> session,
            @Body PublishFlowCommand command
    ) {
        Flow draft = flowService.save(
                session,
                requireCommand(command)
        );
        return HttpResponse.ok(draftView(session, draft));
    }

    @Post("/flows/preview")
    public DefinitionView preview(
            @UserSession Session<User> session,
            @Body PublishFlowCommand command
    ) {
        return new DefinitionView(
                YamlParser.parse(requireCommand(command).source())
        );
    }

    @Get("/flows/{flowKey}")
    public DraftView draft(
            @UserSession Session<User> session,
            String flowKey
    ) {
        return draftView(session, requireDraft(session, flowKey));
    }

    @Put("/flows/{flowKey}")
    public DraftView saveDraft(
            @UserSession Session<User> session,
            String flowKey,
            @Body PublishFlowCommand command
    ) {
        PublishFlowCommand checked = requireCommand(command);
        Flow draft = flowService.save(
                session,
                PublishFlowCommand.from(
                        flowKey,
                        checked.source(),
                        checked.draft()
                )
        );
        return draftView(session, draft);
    }

    @Post("/flows/{flowKey}/deploy")
    public DraftView deploy(
            @UserSession Session<User> session,
            String flowKey
    ) {
        Flow deployed = flowService.save(
                session,
                PublishFlowCommand.from(flowKey, false)
        );
        return DraftView.from(
                requireDraft(session, flowKey),
                deployed
        );
    }

    @Get("/flows/{flowKey}/reversions/{reversion}")
    public FlowView flowReversion(
            @UserSession Session<User> session,
            String flowKey,
            long reversion
    ) {
        Flow flow = flowService.flow(
                session,
                flowKey,
                reversion
        ).orElseThrow(() -> notFound(
                "Flow reversion does not exist: "
                        + flowKey + "@" + reversion
        ));
        return FlowView.from(flow);
    }

    @Get("/flows/{flowKey}/reversions/{reversion}/definition")
    public DefinitionView flowDefinition(
            @UserSession Session<User> session,
            String flowKey,
            long reversion
    ) {
        Flow flow = flowService.flow(
                session,
                flowKey,
                reversion
        ).orElseThrow(() -> notFound(
                "Flow reversion does not exist: "
                        + flowKey + "@" + reversion
        ));
        return new DefinitionView(
                flowDefinitionSerializer.definition(flow)
        );
    }

    @Delete("/flows/{flowKey}")
    public HttpResponse<?> deleteFlow(
            @UserSession Session<User> session,
            String flowKey,
            @QueryValue(defaultValue = "true") Boolean draft
    ) {
        flowService.delete(session, flowKey, draft);
        return HttpResponse.noContent();
    }

    @Post("/flows/{flowKey}/executions")
    public HttpResponse<Create> start(
            @UserSession Session<User> session,
            String flowKey,
            @Body Map<String, Object> body
    ) {
        Map<String, ?> inputs = bodyMap(body, "inputs");
        Create command = executionService.create(
                session,
                flowKey,
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
                                FlowController::createdAt
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

    private DraftView draftView(
            Session<User> session,
            Flow draft
    ) {
        Flow deployed = flowService.latestFlow(
                session,
                draft.key()
        ).orElse(null);
        return DraftView.from(draft, deployed);
    }

    private Flow requireDraft(
            Session<User> session,
            String flowKey
    ) {
        return flowService.draft(session, flowKey)
                .orElseThrow(() -> notFound(
                        "Flow draft does not exist: " + flowKey
                ));
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

    private static PublishFlowCommand requireCommand(
            PublishFlowCommand command
    ) {
        if (command == null) {
            throw new IllegalArgumentException(
                    "Command body must not be empty"
            );
        }
        return command;
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
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(
                        "Command field keys must be strings: " + field
                );
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static long createdAt(Execution execution) {
        return execution.state().history().getFirst().date();
    }

    private static HttpStatusException notFound(String message) {
        return new HttpStatusException(HttpStatus.NOT_FOUND, message);
    }
}
