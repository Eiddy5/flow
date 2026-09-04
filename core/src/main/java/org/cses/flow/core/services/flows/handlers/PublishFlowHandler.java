package org.cses.flow.core.services.flows.handlers;

import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolationException;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowId;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.repositories.flows.FlowRepository;
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
import java.util.Set;

@Singleton
public class PublishFlowHandler implements CommandHandler<
        Session<User>,
        User,
        Flow,
        PublishFlowCommand
        > {

    FlowRepository repository;
    ModelValidator modelValidator;

    /**
     * Creates a publish handler backed by the Flow Repository and Task model
     * validator.
     *
     * @param repository Repository used for all Flow reads and appended saves;
     *        must not be {@code null}
     * @param modelValidator validator used for deployed Task definitions; must
     *        not be {@code null}
     */
    public PublishFlowHandler(
            FlowRepository repository,
            ModelValidator modelValidator
    ) {
        this.repository = repository;
        this.modelValidator = modelValidator;
    }

    /**
     * Returns the command type routed to this handler.
     *
     * @return the non-null {@link PublishFlowCommand} class
     */
    @Override
    public Class<PublishFlowCommand> type() {
        return PublishFlowCommand.class;
    }

    /**
     * Resolves and validates source, applies the requested draft or deployed
     * domain transition, then returns the Repository-appended Flow snapshot.
     *
     * @param context non-null command, Session and transaction context
     * @return a detached Flow with the new database row id and version
     * @throws IllegalArgumentException when source fields or Task definitions
     *         are invalid
     * @throws WorkflowException when a referenced draft is absent, a deployed
     *         lifecycle rule fails, or persistence rejects the new row
     */
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

    /**
     * Creates or revises the current draft state and appends it through the
     * Repository.
     *
     * @param context non-null command context and caller-owned transaction
     * @param companyId non-blank tenant identifier from the Session
     * @param source non-blank raw draft source retained without modification
     * @param parsed non-null transient parsed fields; when no active draft
     *        exists this object is initialized in place, otherwise its fields
     *        are only read while the restored draft is revised
     * @return the appended draft with its Repository-assigned id and version
     * @throws WorkflowException when the existing draft cannot be revised or
     *         the appended row is rejected
     */
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
        return repository.save(context.dsl(), draft);
    }

    /**
     * Validates a deployed definition against the latest deployed Flow and
     * appends it through the Repository.
     *
     * @param context non-null command context and caller-owned transaction
     * @param companyId non-blank tenant identifier from the Session
     * @param flowKey non-blank stable Flow key
     * @param source non-blank raw source retained without modification
     * @param parsed non-null transient parsed definition initialized in place
     *        before a detached persisted snapshot is returned
     * @return the appended deployed Flow with its assigned id and version
     * @throws WorkflowException when the latest deployed state rejects the
     *         definition or persistence rejects the new row
     */
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
        return repository.save(context.dsl(), parsed);
    }

    /**
     * Uses the command source when present, otherwise restores the latest
     * active draft source in the same tenant.
     *
     * @param context non-null command context and caller-owned transaction
     * @param companyId non-blank tenant identifier from the Session
     * @param command non-null publish command
     * @return the command source as supplied, or the stored draft source
     * @throws IllegalArgumentException when both source and usable key are
     *         absent
     * @throws WorkflowException when source is omitted and no active draft
     *         exists for the key
     */
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

    /**
     * Parses deployed source strictly and strips non-definition Flow fields
     * only when accepting a draft.
     *
     * @param source non-blank raw Flow source
     * @param draft {@code true} to ignore Flow system fields and allow an
     *        unbindable Task list in raw draft source; {@code false} to bind
     *        the complete deployed definition strictly
     * @return parsed user-managed Flow definition fields
     * @throws RuntimeException when deployed source or public Flow fields
     *         cannot be bound
     */
    private Flow parse(String source, boolean draft) {
        if (draft) {
            return parseDraft(source);
        }
        return YamlParser.parse(source, Flow.class);
    }

    /**
     * Parses a draft while preserving its raw source and preventing supplied
     * row, Repository-managed and domain-managed fields from entering the
     * transient Flow. If Task binding fails, only definition fields are
     * materialized.
     *
     * @param source non-blank raw draft source
     * @return parsed draft containing only source-owned definition fields
     * @throws RuntimeException when the YAML or public Flow fields cannot be
     *         bound
     */
    private Flow parseDraft(String source) {
        // A draft keeps the source even when a Task plugin is not currently
        // bindable. Public Flow fields still go through the same Jackson
        // configuration and are validated before persistence.
        Map<String, Object> fields = new LinkedHashMap<>(
                YamlParser.parse(source)
        );
        fields.keySet().removeAll(Set.of(
                "id",
                "companyId",
                "version",
                "draft",
                "source",
                "status",
                "creator",
                "createdAt",
                "updater",
                "updatedAt",
                "deleter",
                "deletedAt"
        ));
        try {
            return YamlParser.bind(fields, Flow.class);
        } catch (RuntimeException bindingFailure) {
            fields.remove("tasks");
            try {
                return YamlParser.bind(fields, Flow.class);
            } catch (RuntimeException fallbackFailure) {
                fallbackFailure.addSuppressed(bindingFailure);
                throw fallbackFailure;
            }
        }
    }

    /**
     * Validates every parsed Flow input definition without modifying the
     * Flow or its immutable input list.
     *
     * @param flow non-null parsed Flow whose inputs are validated
     * @throws IllegalArgumentException when an input definition is invalid
     */
    private void validateInputs(Flow flow) {
        flow.inputs().forEach(input -> input.validateDefinition());
    }

    /**
     * Applies model validation to every top-level deployed Task without
     * modifying the Flow or its immutable Task list.
     *
     * @param flow non-null deployed definition candidate
     * @throws IllegalArgumentException when a Task violates its model
     *         constraints
     */
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
