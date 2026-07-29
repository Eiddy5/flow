package org.cses.flow.core.services.externaltasks;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.commands.externaltasks.CompleteExternalTaskCommand;
import org.cses.flow.core.commands.shared.CommandExecutor;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.externaltasks.ExternalTask;
import org.cses.flow.core.queries.externaltasks.ExternalTaskQueryHandler;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Singleton
public final class ExternalTaskService {

    private final CommandExecutor commandExecutor;
    private final ExternalTaskQueryHandler queryHandler;

    @Inject
    public ExternalTaskService(
        CommandExecutor commandExecutor,
        ExternalTaskQueryHandler queryHandler
    ) {
        this.commandExecutor = commandExecutor;
        this.queryHandler = queryHandler;
    }

    public <S extends Session<U>, U extends User>
        Execution complete(
            S session,
            String externalTaskId,
            Map<String, Object> outputs
        ) {

        return commandExecutor.execute(
            session,
            new CompleteExternalTaskCommand(externalTaskId, outputs)
        );
    }

    public <S extends Session<U>, U extends User>
        Optional<ExternalTask> externalTask(
            S session,
            String externalTaskId
        ) {

        return queryHandler.externalTask(session, externalTaskId);
    }

    public <S extends Session<U>, U extends User>
        Optional<ExternalTask> waitingForTaskRun(
            S session,
            String taskRunId
        ) {

        return queryHandler.waitingForTaskRun(session, taskRunId);
    }

    /**
     * Returns the current user's tenant-visible WAITING external tasks.
     */
    public <S extends Session<U>, U extends User>
        List<ExternalTask> waitingTasks(S session) {

        return queryHandler.waitingTasks(session);
    }
}
