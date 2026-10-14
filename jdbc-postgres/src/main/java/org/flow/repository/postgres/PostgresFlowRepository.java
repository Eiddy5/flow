package org.flow.repository.postgres;

import java.util.Map;

import org.jooq.Condition;

import org.flow.core.events.CrudEvent;
import org.flow.core.models.QueryFilter;
import org.flow.core.models.flows.FlowInterface;
import org.flow.core.models.validations.ModelValidator;
import org.flow.core.repositories.RepositoryBean;
import org.flow.core.services.FlowParsingService;
import org.flow.jdbc.repository.AbstractJdbcFlowRepository;

import io.micronaut.context.event.ApplicationEventPublisher;
import jakarta.inject.Inject;
import jakarta.inject.Named;

@RepositoryBean
@PostgresRepositoryEnabled
public class PostgresFlowRepository extends AbstractJdbcFlowRepository {
    @Inject
    public PostgresFlowRepository(@Named("flows") PostgresRepository<FlowInterface> repository,
        ModelValidator modelValidator,
        ApplicationEventPublisher<CrudEvent<FlowInterface>> eventPublisher,
        FlowParsingService flowParsingService) {
        super(repository, modelValidator, eventPublisher, flowParsingService);
    }

    @Override
    protected Condition findCondition(String query, Map<String, String> labels) {
        return PostgresFlowRepositoryService.findCondition(this.jdbcRepository, query, labels);
    }

    @Override
    protected Condition findCondition(Object value, QueryFilter.Op operation) {
        return PostgresFlowRepositoryService.findCondition(value, operation);
    }

    @Override
    protected Condition findSourceCodeCondition(String query) {
        return PostgresFlowRepositoryService.findSourceCodeCondition(this.jdbcRepository, query);
    }

    @Override
    protected Condition findTriggerClassCondition(Class<? extends org.flow.core.models.triggers.AbstractTrigger> triggerClass) {
        return PostgresFlowRepositoryService.findTriggerClassCondition(triggerClass);
    }
}
