package org.cses.flow.controller.flow;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.exceptions.HttpStatusException;
import jakarta.inject.Inject;
import org.cses.flow.controller.ApiModels.ErrorView;
import org.cses.flow.controller.flow.FlowModels.*;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.serializers.YamlParser;
import org.cses.flow.core.services.flows.FlowService;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
import org.paas.session.Session;
import org.paas.session.User;
import org.paas.session.bind.UserSession;

import java.util.List;

/**
 * User-facing HTTP adapter for the authenticated Flow management surface.
 */
@Controller("/api")
public class FlowController {

    private final FlowService flowService;

    @Inject
    public FlowController(FlowService flowService) {
        this.flowService = flowService;
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
                YamlParser.parse(flow.source())
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

    private static HttpStatusException notFound(String message) {
        return new HttpStatusException(HttpStatus.NOT_FOUND, message);
    }
}
