package org.cses.flow.core.services.flows.handlers;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.services.flows.commands.DeleteFlowCommand;
import org.cses.flow.core.services.CommandContext;
import org.cses.flow.core.services.CommandHandler;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowId;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.services.shared.SessionValidation;
import org.cses.flow.core.utils.TimeUtil;
import org.paas.session.Session;
import org.paas.session.User;

@Singleton
public class DeleteFlowHandler implements CommandHandler<
        Session<User>,
        User,
        Flow,
        DeleteFlowCommand
        > {

    private FlowRepository flowRepository;

    @Inject
    public DeleteFlowHandler(
            FlowRepository flowRepository
    ) {
        this.flowRepository = flowRepository;
    }

    @Override
    public Class<DeleteFlowCommand> type() {
        return DeleteFlowCommand.class;
    }

    /**
     * Marks the selected latest Flow state deleted and appends that deletion
     * as a new version.
     *
     * @param context non-null command, Session and caller-owned transaction;
     *        {@code command.draft=true} selects the latest draft and
     *        {@code false} selects the latest deployed definition
     * @return the appended deleted Flow with its assigned id and version
     * @throws WorkflowException when the selected Flow does not exist, was
     *         already deleted, belongs to another tenant, or cannot be saved
     */
    @Override
    public Flow handle(
            CommandContext<
                    Session<User>,
                    User,
                    Flow,
                    DeleteFlowCommand
                    > context
    ) {
        DeleteFlowCommand command = context.command();
        String companyId = SessionValidation.requireCompanyId(
                context.session()
        );
        String flowKey = command.key().trim();
        FlowId flowId = FlowId.from(companyId, flowKey);
        Flow flow = command.draft()
            ? flowRepository.findDraftByFlowId(
                context.dsl(),
                flowId
            ).orElseThrow(() -> new WorkflowException(
                "Draft Flow does not exist: " + flowKey
            ))
            : flowRepository.findLatestByFlowId(
                context.dsl(),
                flowId
            ).orElseThrow(() -> new WorkflowException(
                "Flow does not exist: " + flowKey
            ));
        flow.delete(context.session(), TimeUtil.now());
        return flowRepository.save(context.dsl(), flow);
    }
}
