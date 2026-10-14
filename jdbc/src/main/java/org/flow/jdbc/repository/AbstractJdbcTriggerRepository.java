package org.flow.jdbc.repository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Function;

import org.jooq.*;
import org.jooq.impl.DSL;

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

    private static final Field<Object> NAMESPACE_FIELD = field("namespace");
    private static final Field<Long> NEXT_EVALUATION_EPOCH_FIELD = field("next_evaluation_epoch", Long.class);
    private static final Field<Boolean> LOCKED_FIELD = field("locked", Boolean.class);
    private static final Field<Integer> VNODE_FIELD = field("vnode", Integer.class);
    private static final Field<Object> FLOW_ID_FIELD = field("flow_id");
    private static final Field<Object> WORKER_ID_FIELD = field("worker_id");
    private static final Field<Object> VALUE_FIELD = field("value");
    private static final String NEXT_EVALUATION_DATE_COLUMN = "next_evaluation_date";
    private static final String LAST_TRIGGERED_DATE_COLUMN = "last_triggered_date";
    private static final Field<Object> KEY_FIELD = DSL.field(DSL.quotedName("key"));
    public AbstractJdbcTriggerRepository(org.flow.jdbc.AbstractJdbcRepository<TriggerState> jdbcRepository) {
        super(jdbcRepository);
    }

    @Override
    public Optional<TriggerState> findByIdWithoutAcl(TriggerId trigger) {
        return findOne(DSL.noCondition(), KEY_FIELD.eq(trigger.uid()));
    }

    @Override
    public List<TriggerState> findAll(String tenantId) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                var select = DSL
                    .using(configuration)
                    .select(VALUE_FIELD)
                    .from(this.jdbcRepository.getTable())
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
                SelectJoinStep<Record1<Object>> select = DSL
                    .using(configuration)
                    .select(VALUE_FIELD)
                    .from(this.jdbcRepository.getTable());

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
                    .insertInto(this.jdbcRepository.getTable())
                    .set(KEY_FIELD, trigger.uid())
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
                    .delete(this.jdbcRepository.getTable())
                    .where(KEY_FIELD.eq(trigger.uid()))
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
            condition = condition.and(DSL.or(NAMESPACE_FIELD.eq(namespace), NAMESPACE_FIELD.startsWith(namespace + ".")));
        }

        if (flowId != null) {
            condition = condition.and(FLOW_ID_FIELD.eq(flowId));
        }

        if (workerId != null) {
            condition = condition.and(WORKER_ID_FIELD.eq(workerId));
        }

        return findPage(pageable, tenantId, condition);
    }

    @Override
    public Flux<TriggerState> find(String tenantId, List<QueryFilter> filters) {
        var condition = filter(filters, null, Resource.TRIGGER);
        return findAsync(tenantId, condition);
    }

    protected Condition fullTextCondition(String query) {
        return query == null ? DSL.noCondition() : jdbcRepository.fullTextCondition(List.of("fulltext"), query);
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
        Map<String, String> mapper = Map.of("nextExecutionDate", NEXT_EVALUATION_DATE_COLUMN);

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
                    .select(VALUE_FIELD)
                    .from(this.jdbcRepository.getTable())
                    .where(VNODE_FIELD.in(vNodes))
                    .fetch()
            )
            .map(r -> this.jdbcRepository.deserialize(r.get("value", String.class)));
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
                    .select(VALUE_FIELD)
                    .from(this.jdbcRepository.getTable())
                    .where(NEXT_EVALUATION_EPOCH_FIELD.le(epochMilli).or(NEXT_EVALUATION_EPOCH_FIELD.isNull()))
                    .and(LOCKED_FIELD.isNull().or(LOCKED_FIELD.eq(locked)))
                    .and(VNODE_FIELD.in(vNodes))
                    .orderBy(NEXT_EVALUATION_EPOCH_FIELD.asc())
                    .fetch()
            )
            .map(r -> this.jdbcRepository.deserialize(r.get("value", String.class)));
    }

    @Override
    protected Name getColumnName(QueryFilter.Field field) {
        if (field == QueryFilter.Field.SOURCE) {
            return DSL.quotedName("type");
        }
        return super.getColumnName(field);
    }

    @Override
    protected Condition lockedCondition(Object value, QueryFilter.Op operation) {
        boolean lockedValue = TypeConverter.toBoolean(value);
        return switch (operation) {
            case EQUALS -> DSL.field(DSL.quotedName("locked")).eq(lockedValue);
            default -> throw new InvalidQueryFiltersException("Unsupported operation for LOCKED: " + operation);
        };
    }

    @Override
    protected Condition lastTriggeredDateCondition(Object value, QueryFilter.Op operation) {
        return triggerDateFieldCondition(value, operation, LAST_TRIGGERED_DATE_COLUMN, QueryFilter.Field.LAST_TRIGGERED_DATE);
    }

    @Override
    protected Condition nextExecutionDateCondition(Object value, QueryFilter.Op operation) {
        return triggerDateFieldCondition(value, operation, NEXT_EVALUATION_DATE_COLUMN, QueryFilter.Field.NEXT_EXECUTION_DATE);
    }

    private Condition triggerDateFieldCondition(Object value, QueryFilter.Op operation, String column, QueryFilter.Field field) {
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
