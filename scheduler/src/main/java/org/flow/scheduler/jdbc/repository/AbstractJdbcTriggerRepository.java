package org.flow.scheduler.jdbc.repository;

import org.flow.core.jdbc.repository.AbstractJdbcCrudRepository;
import org.flow.core.jdbc.repository.AbstractJdbcRepository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Function;

import org.jooq.*;
import org.jooq.impl.DSL;

import org.flow.gen.flow.Tables;
import org.flow.gen.flow.tables.TriggersTable;
import org.flow.core.exceptions.InvalidQueryFiltersException;
import org.flow.core.exceptions.TypeConversionException;
import org.flow.core.models.QueryFilter;
import org.flow.core.models.QueryFilter.Resource;
import org.flow.core.models.triggers.TriggerId;
import org.flow.core.repositories.ArrayListTotal;
import org.flow.core.repositories.TriggerRepositoryInterface;
import org.flow.core.scheduler.model.TriggerState;
import org.flow.core.scheduler.store.TriggerStateStore;
import org.flow.core.utils.TypeConverter;

import io.micronaut.data.model.Pageable;
import reactor.core.publisher.Flux;

public abstract class AbstractJdbcTriggerRepository extends AbstractJdbcCrudRepository<TriggerState> implements TriggerRepositoryInterface, TriggerStateStore {

    public AbstractJdbcTriggerRepository(org.flow.core.jdbc.AbstractJdbcRepository<TriggerState> jdbcRepository) {
        super(jdbcRepository);
    }

    @Override
    protected TriggersTable table() {
        return jdbcRepository.getTable(Tables.TRIGGERS);
    }

    @Override
    public Optional<TriggerState> findByIdWithoutAcl(TriggerId trigger) {
        return findOne(DSL.noCondition(), table().KEY.eq(trigger.uid()));
    }

    @Override
    public List<TriggerState> findAll(String tenantId) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                var select = DSL
                    .using(configuration)
                    .select(table().VALUE)
                    .from(table())
                    .where(this.defaultFilter(tenantId));

                return this.jdbcRepository.fetch(select);
            });
    }

    @Override
    public List<TriggerState> findAllForAllTenants() {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                SelectJoinStep<Record1<JSONB>> select = DSL
                    .using(configuration)
                    .select(table().VALUE)
                    .from(table());

                return this.jdbcRepository.fetch(select);
            });
    }

    @Override
    public TriggerState create(TriggerState trigger) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                DSL.using(configuration)
                    .insertInto(table())
                    .set(table().KEY, trigger.uid())
                    .set(this.jdbcRepository.persistFields(trigger))
                    .execute();

                return trigger;
            });
    }

    @Override
    public void delete(TriggerId trigger) {
        this.jdbcRepository
            .getDslContextWrapper()
            .transaction(configuration ->
            {
                DSL.using(configuration)
                    .delete(table())
                    .where(table().KEY.eq(trigger.uid()))
                    .execute();
            });
    }

    @Override
    public ArrayListTotal<TriggerState> find(Pageable pageable, String tenantId, List<QueryFilter> filters) {
        var condition = filter(filters, null, Resource.TRIGGER);
        return findPage(pageable, tenantId, condition);
    }

    @Override
    public ArrayListTotal<TriggerState> find(Pageable pageable, String query, String tenantId, String namespace, String flowId, String workerId) {
        var condition = this.fullTextCondition(query).and(this.defaultFilter());

        if (namespace != null) {
            condition = condition.and(DSL.or(table().NAMESPACE.eq(namespace), table().NAMESPACE.startsWith(namespace + ".")));
        }

        if (flowId != null) {
            condition = condition.and(table().FLOW_ID.eq(flowId));
        }

        if (workerId != null) {
            condition = condition.and(table().WORKER_ID.eq(workerId));
        }

        return findPage(pageable, tenantId, condition);
    }

    @Override
    public Flux<TriggerState> find(String tenantId, List<QueryFilter> filters) {
        var condition = filter(filters, null, Resource.TRIGGER);
        return findAsync(tenantId, condition);
    }

    protected Condition fullTextCondition(String query) {
        return query == null ? DSL.noCondition() : jdbcRepository.fullTextCondition(List.of(table().FULLTEXT), query);
    }

    @Override
    protected Condition findQueryCondition(String query) {
        return fullTextCondition(query);
    }

    @Override
    protected Condition defaultFilter(String tenantId, boolean allowDeleted) {
        return buildTenantCondition(tenantId).and(aclCondition(Resource.TRIGGER));
    }

    @Override
    protected Condition defaultFilter() {
        return DSL.noCondition();
    }

    @Override
    public Function<String, String> sortMapping() throws IllegalArgumentException {
        // nextExecutionDate needs remapping, since its real column is next_evaluation_date.
        Map<String, String> mapper = Map.of("nextExecutionDate", table().NEXT_EVALUATION_DATE.getName());

        return s -> mapper.getOrDefault(s, s);
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public List<TriggerState> findAllForVNodes(Set<Integer> vNodes) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(
                configuration -> DSL.using(configuration)
                    .select(table().VALUE)
                    .from(table())
                    .where(table().VNODE.in(vNodes))
                    .fetch()
            )
            .map(r -> this.jdbcRepository.deserialize(r.get(table().VALUE).data()));
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public List<TriggerState> findTriggersEligibleForScheduling(ZonedDateTime now, Set<Integer> vNodes, boolean locked) {
        final long epochMilli = now.toInstant().toEpochMilli();
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(
                configuration -> DSL.using(configuration)
                    .select(table().VALUE)
                    .from(table())
                    .where(table().NEXT_EVALUATION_EPOCH.le(epochMilli).or(table().NEXT_EVALUATION_EPOCH.isNull()))
                    .and(table().LOCKED.isNull().or(table().LOCKED.eq(locked)))
                    .and(table().VNODE.in(vNodes))
                    .orderBy(table().NEXT_EVALUATION_EPOCH.asc())
                    .fetch()
            )
            .map(r -> this.jdbcRepository.deserialize(r.get(table().VALUE).data()));
    }

    @Override
    protected Field<?> getColumnField(QueryFilter.Field field) {
        if (field == QueryFilter.Field.SOURCE) {
            return table().TYPE;
        }
        return super.getColumnField(field);
    }

    @Override
    protected Condition lockedCondition(Object value, QueryFilter.Op operation) {
        boolean lockedValue = TypeConverter.toBoolean(value);
        return switch (operation) {
            case EQUALS -> table().LOCKED.eq(lockedValue);
            default -> throw new InvalidQueryFiltersException("Unsupported operation for LOCKED: " + operation);
        };
    }

    @Override
    protected Condition lastTriggeredDateCondition(Object value, QueryFilter.Op operation) {
        return triggerDateFieldCondition(value, operation, table().LAST_TRIGGERED_DATE, QueryFilter.Field.LAST_TRIGGERED_DATE);
    }

    @Override
    protected Condition nextExecutionDateCondition(Object value, QueryFilter.Op operation) {
        return triggerDateFieldCondition(value, operation, table().NEXT_EVALUATION_DATE, QueryFilter.Field.NEXT_EXECUTION_DATE);
    }

    private Condition triggerDateFieldCondition(Object value, QueryFilter.Op operation, Field<OffsetDateTime> column, QueryFilter.Field field) {
        // Accept ISO-8601 durations (e.g. PT24H) as "last N hours" — same semantics as TIME_RANGE
        try {
            Duration duration = TypeConverter.toDuration(value);
            ZonedDateTime threshold = ZonedDateTime.now().minus(duration);
            return applyDateCondition(threshold.toOffsetDateTime(), QueryFilter.Op.GREATER_THAN_OR_EQUAL_TO, column);
        } catch (TypeConversionException ignored) {
            // Not a duration — fall through to absolute date parsing
        }
        try {
            OffsetDateTime dateTime = TypeConverter.toZonedDateTime(value).toOffsetDateTime();
            return applyDateCondition(dateTime, operation, column);
        } catch (TypeConversionException e) {
            throw new InvalidQueryFiltersException("Invalid date or duration value for " + field + ": " + value);
        }
    }

}
