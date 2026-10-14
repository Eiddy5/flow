package org.flow.repository.postgres;

import java.util.*;

import org.jooq.Condition;

import org.flow.core.contexts.configuration.SystemFlowsConfiguration;
import org.flow.core.events.CrudEvent;
import org.flow.core.models.QueryFilter;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.State;
import org.flow.core.repositories.RepositoryBean;
import org.flow.core.utils.Either;
import org.flow.jdbc.repository.AbstractJdbcExecutionRepository;

import io.micronaut.context.event.ApplicationEventPublisher;
import jakarta.inject.Inject;
import jakarta.inject.Named;

@RepositoryBean
@PostgresRepositoryEnabled
public class PostgresExecutionRepository extends AbstractJdbcExecutionRepository {
    @Inject
    public PostgresExecutionRepository(@Named("executions") PostgresRepository<Execution> repository,
        ApplicationEventPublisher<CrudEvent<Execution>> eventPublisher,
        SystemFlowsConfiguration systemFlowsConfiguration) {
        super(repository, eventPublisher, systemFlowsConfiguration);
    }

    @Override
    protected Condition statesFilter(List<State.Type> state) {
        return PostgresExecutionRepositoryService.statesFilter(state);
    }

    @Override
    protected Condition findCondition(String query, Map<String, String> labels) {
        return PostgresExecutionRepositoryService.findCondition(this.jdbcRepository, query, labels);
    }

    @Override
    public Condition findLabelCondition(Either<Map<?, ?>, String> input, QueryFilter.Op operation) {
        return PostgresExecutionRepositoryService.findLabelCondition(input, operation);
    }

}
