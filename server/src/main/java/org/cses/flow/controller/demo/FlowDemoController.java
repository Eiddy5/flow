package org.cses.flow.controller.demo;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.exceptions.HttpStatusException;
import jakarta.inject.Inject;
import org.cses.flow.controller.demo.FlowDemoModels.DefinitionView;
import org.cses.flow.controller.demo.FlowDemoModels.DraftView;
import org.cses.flow.controller.demo.FlowDemoModels.ErrorView;
import org.cses.flow.controller.demo.FlowDemoModels.ExecutionView;
import org.cses.flow.controller.demo.FlowDemoModels.FlowView;
import org.cses.flow.controller.demo.FlowDemoModels.InputTypeView;
import org.cses.flow.controller.demo.FlowDemoModels.ResumeRequest;
import org.cses.flow.controller.demo.FlowDemoModels.SaveDraftRequest;
import org.cses.flow.controller.demo.FlowDemoModels.SessionView;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowDraft;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.serializers.YamlParser;
import org.cses.flow.core.services.executions.ExecutionService;
import org.cses.flow.core.services.flows.FlowService;
import org.paas.session.Session;
import org.paas.session.User;
import org.paas.session.bind.UserSession;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * User-facing HTTP adapter used by the standalone Flow Studio demo.
 */
@Controller("/api/demo")
@Requires(property = "flow.studio.enabled", value = "true")
public final class FlowDemoController {

    private final FlowService flowService;
    private final ExecutionService executionService;
    private final YamlParser yamlParser;

    @Inject
    public FlowDemoController(
        FlowService flowService,
        ExecutionService executionService,
        YamlParser yamlParser
    ) {
        this.flowService = flowService;
        this.executionService = executionService;
        this.yamlParser = yamlParser;
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
    public HttpResponse<DraftView> createDraft(
        @UserSession Session<User> session,
        @Body SaveDraftRequest request
    ) {
        FlowDraft draft = flowService.saveDraft(
            session,
            requireRequest(request).getRaw()
        );
        return HttpResponse.created(DraftView.from(draft, null));
    }

    @Post("/flows/preview")
    public DefinitionView preview(
        @UserSession Session<User> session,
        @Body SaveDraftRequest request
    ) {
        return new DefinitionView(
            yamlParser.parse(requireRequest(request).getRaw())
        );
    }

    @Get("/flows/{flowId}")
    public DraftView draft(
        @UserSession Session<User> session,
        String flowId
    ) {
        return draftView(session, requireDraft(session, flowId));
    }

    @Put("/flows/{flowId}")
    public DraftView updateDraft(
        @UserSession Session<User> session,
        String flowId,
        @Body SaveDraftRequest request
    ) {
        SaveDraftRequest checked = requireRequest(request);
        FlowDraft draft;
        if (checked.getExpectedLockVersion() == null) {
            draft = flowService.saveDraft(
                session,
                flowId,
                checked.getRaw()
            );
        } else {
            draft = flowService.saveDraft(
                session,
                flowId,
                checked.getExpectedLockVersion(),
                checked.getRaw()
            );
        }
        return draftView(session, draft);
    }

    @Post("/flows/{flowId}/deploy")
    public DraftView deploy(
        @UserSession Session<User> session,
        String flowId
    ) {
        Flow deployed = flowService.deploy(session, flowId);
        return DraftView.from(
            requireDraft(session, flowId),
            deployed
        );
    }

    @Get("/flows/{flowId}/reversions/{reversion}")
    public FlowView flowReversion(
        @UserSession Session<User> session,
        String flowId,
        long reversion
    ) {
        Flow flow = flowService.flow(
            session,
            flowId,
            reversion
        ).orElseThrow(() -> notFound(
            "Flow reversion does not exist: "
                + flowId + "@" + reversion
        ));
        return FlowView.from(flow);
    }

    @Delete("/flows/{flowId}/draft")
    public HttpResponse<?> deleteDraft(
        @UserSession Session<User> session,
        String flowId
    ) {
        flowService.deleteDraft(session, flowId);
        return HttpResponse.noContent();
    }

    @Delete("/flows/{flowId}")
    public HttpResponse<?> deleteFlow(
        @UserSession Session<User> session,
        String flowId
    ) {
        if (flowService.latestFlow(session, flowId).isPresent()) {
            flowService.delete(session, flowId);
        } else {
            flowService.deleteDraft(session, flowId);
        }
        return HttpResponse.noContent();
    }

    @Post("/flows/{flowId}/executions")
    public HttpResponse<ExecutionView> start(
        @UserSession Session<User> session,
        String flowId
    ) {
        Execution execution = executionService.create(session, flowId);
        return HttpResponse.created(ExecutionView.from(execution));
    }

    @Get("/executions")
    public List<ExecutionView> executions(
        @UserSession Session<User> session
    ) {
        return executionService.executions(session).stream()
            .sorted(
                Comparator.comparingLong(
                    FlowDemoController::createdAt
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
        @Body ResumeRequest request
    ) {
        Map<String, Object> outputs =
            request == null || request.getOutputs() == null
                ? Map.of()
                : request.getOutputs();
        return ExecutionView.from(executionService.resume(
            session,
            executionId,
            taskRunId,
            outputs
        ));
    }

    @Error(exception = WorkflowException.class)
    public HttpResponse<ErrorView> workflowError(
        HttpRequest<?> request,
        WorkflowException exception
    ) {
        return HttpResponse.status(HttpStatus.CONFLICT)
            .body(new ErrorView(exception.getMessage()));
    }

    @Error(exception = IllegalArgumentException.class)
    public HttpResponse<ErrorView> invalidRequest(
        HttpRequest<?> request,
        IllegalArgumentException exception
    ) {
        return HttpResponse.badRequest(
            new ErrorView(exception.getMessage())
        );
    }

    private DraftView draftView(
        Session<User> session,
        FlowDraft draft
    ) {
        Flow deployed = flowService.latestFlow(
            session,
            draft.id()
        ).orElse(null);
        return DraftView.from(draft, deployed);
    }

    private FlowDraft requireDraft(
        Session<User> session,
        String flowId
    ) {
        return flowService.draft(session, flowId)
            .orElseThrow(() -> notFound(
                "Flow draft does not exist: " + flowId
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

    private static SaveDraftRequest requireRequest(
        SaveDraftRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException(
                "Request body must not be empty"
            );
        }
        return request;
    }

    private static long createdAt(Execution execution) {
        return execution.state().history().getFirst().date();
    }

    private static HttpStatusException notFound(String message) {
        return new HttpStatusException(HttpStatus.NOT_FOUND, message);
    }
}
