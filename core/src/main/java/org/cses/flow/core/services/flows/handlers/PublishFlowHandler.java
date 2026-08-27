package org.cses.flow.core.services.flows.handlers;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolationException;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowId;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.serializers.JacksonMapper;
import org.cses.flow.core.serializers.YamlParser;
import org.cses.flow.core.services.CommandContext;
import org.cses.flow.core.services.CommandHandler;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
import org.cses.flow.core.services.shared.SessionValidation;
import org.cses.flow.core.utils.TimeUtil;
import org.cses.flow.core.validations.ModelValidator;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.LinkedHashMap;
import java.util.Map;

@Singleton
public class PublishFlowHandler implements CommandHandler<
        Session<User>,
        User,
        Flow,
        PublishFlowCommand
        > {

    FlowRepository repository;
    JacksonMapper jacksonMapper;
    ModelValidator modelValidator;

    public PublishFlowHandler(
            FlowRepository repository,
            JacksonMapper jacksonMapper,
            ModelValidator modelValidator
    ) {
        this.repository = repository;
        this.jacksonMapper = jacksonMapper;
        this.modelValidator = modelValidator;
    }

    @Override
    public Class<PublishFlowCommand> type() {
        return PublishFlowCommand.class;
    }

    @Override
    public Flow handle(
            CommandContext<
                    Session<User>,
                    User,
                    Flow,
                    PublishFlowCommand
                    > context
    ) {
        PublishFlowCommand command = context.command();
        String companyId = SessionValidation.requireCompanyId(
                context.session()
        );
        String source = resolveSource(context, companyId, command);
        Flow parsed = parse(source, command.draft());
        validateInputs(parsed);
        if (!command.draft()) {
            validateTasks(parsed);
        }
        String flowKey = parsed.resolveKey(command.key());
        if (command.draft()) {
            return saveDraft(context, companyId, source, parsed);
        }
        return saveDeployed(context, companyId, flowKey, source, parsed);
    }

    private Flow saveDraft(
            CommandContext<Session<User>, User, Flow, PublishFlowCommand>
                    context,
            String companyId,
            String source,
            Flow parsed
    ) {
        FlowId flowId = FlowId.from(companyId, parsed.key());
        Flow draft = repository.findDraftByFlowId(
                context.dsl(),
                flowId
        ).orElse(null);
        if (draft == null) {
            parsed.initialize(context.session(), true, null, source);
            draft = parsed;
        } else {
            draft.revise(
                    parsed.description(),
                    parsed.variables(),
                    parsed.inputs(),
                    parsed.outputs(),
                    parsed.tasks(),
                    source,
                    context.session(),
                    TimeUtil.now()
            );
        }
        repository.save(context.dsl(), draft);
        return draft.copy();
    }

    private Flow saveDeployed(
            CommandContext<Session<User>, User, Flow, PublishFlowCommand>
                    context,
            String companyId,
            String flowKey,
            String source,
            Flow parsed
    ) {
        Flow latest = repository.findLatestByFlowId(
                context.dsl(),
                FlowId.from(companyId, flowKey)
        ).orElse(null);
        parsed.initialize(context.session(), false, latest, source);
        repository.save(context.dsl(), parsed);
        return parsed.copy();
    }

    private String resolveSource(
            CommandContext<Session<User>, User, Flow, PublishFlowCommand>
                    context,
            String companyId,
            PublishFlowCommand command
    ) {
        if (command.source() != null) {
            return command.source();
        }
        if (command.key() == null || command.key().isBlank()) {
            throw new IllegalArgumentException(
                    "Flow key is required when source is omitted"
            );
        }
        return repository.findDraftByFlowId(
                context.dsl(),
                FlowId.from(companyId, command.key().trim())
        ).orElseThrow(() -> new WorkflowException(
                "Draft Flow does not exist: " + command.key()
        )).source();
    }

    private Flow parse(String source, boolean draft) {
        try {
            return YamlParser.parse(source, Flow.class);
        } catch (IllegalArgumentException exception) {
            if (!draft) {
                throw exception;
            }
            return parseDraftFields(source, exception);
        }
    }

    private Flow parseDraftFields(
            String source,
            IllegalArgumentException bindingFailure
    ) {
        // A draft keeps the source even when a Task plugin is not currently
        // bindable. Public Flow fields still go through the same Jackson
        // configuration and are validated before persistence.
        Map<String, Object> fields = new LinkedHashMap<>(
                YamlParser.parse(source)
        );
        fields.remove("tasks");
        try {
            return jacksonMapper.convertValue(fields, Flow.class);
        } catch (RuntimeException fallbackFailure) {
            fallbackFailure.addSuppressed(bindingFailure);
            throw fallbackFailure;
        }
    }

    private void validateInputs(Flow flow) {
        flow.inputs().forEach(input -> input.validateDefinition());
    }

    private void validateTasks(Flow flow) {
        flow.tasks().forEach(task -> {
            try {
                modelValidator.validate(task);
            } catch (ConstraintViolationException exception) {
                throw new IllegalArgumentException(
                        exception.getMessage(),
                        exception
                );
            }
        });
    }
}
