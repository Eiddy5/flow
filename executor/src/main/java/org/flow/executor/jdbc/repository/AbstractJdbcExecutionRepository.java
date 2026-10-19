package org.flow.executor.jdbc.repository;

import org.flow.core.jdbc.repository.AbstractJdbcCrudRepository;
import org.flow.core.jdbc.repository.AbstractJdbcRepository;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Function;

import org.jooq.*;
import org.jooq.Record;
import org.jooq.impl.DSL;

import org.flow.gen.flow.Tables;
import org.flow.gen.flow.enums.StateType;
import org.flow.gen.flow.tables.ExecutionsTable;
import org.flow.core.contexts.configuration.SystemFlowsConfiguration;
import org.flow.core.events.CrudEvent;
import org.flow.core.models.QueryFilter;
import org.flow.core.models.QueryFilter.Resource;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.ExecutionKind;
import org.flow.core.models.flows.FlowScope;
import org.flow.core.models.flows.State;
import org.flow.core.models.triggers.TriggerId;
import org.flow.core.repositories.ArrayListTotal;
import org.flow.core.repositories.ExecutionRepositoryInterface;
import org.flow.core.utils.Either;
import org.flow.core.utils.ListUtils;
import org.flow.executor.ExecutionStateStore;
import org.flow.executor.ExecutorContext;

import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.data.model.Pageable;
import jakarta.annotation.Nullable;
import lombok.SneakyThrows;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

public abstract class AbstractJdbcExecutionRepository extends AbstractJdbcCrudRepository<Execution> implements ExecutionRepositoryInterface, ExecutionStateStore {

    @Override
    protected ExecutionsTable table() {
        return jdbcRepository.getTable(Tables.EXECUTIONS);
    }

    private static final int FETCH_SIZE = 100;

    private Condition normalKindCondition() {
        return table().KIND.isNull().or(table().KIND.eq(ExecutionKind.NORMAL.name()));
    }

    private final ApplicationEventPublisher<CrudEvent<Execution>> eventPublisher;
    private final SystemFlowsConfiguration systemFlowsConfiguration;

    @SuppressWarnings("unchecked")
    public AbstractJdbcExecutionRepository(
        org.flow.core.jdbc.AbstractJdbcRepository<Execution> jdbcRepository,
        ApplicationEventPublisher<CrudEvent<Execution>> eventPublisher,
        SystemFlowsConfiguration systemFlowsConfiguration) {
        super(jdbcRepository);
        this.eventPublisher = eventPublisher;
        this.systemFlowsConfiguration = systemFlowsConfiguration;
    }

    @Override
    protected Condition defaultFilter(String tenantId, boolean allowDeleted) {
        return super.defaultFilter(tenantId, allowDeleted).and(aclCondition(Resource.EXECUTION));
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public Flux<Execution> findAllByTriggerExecutionId(String tenantId,
        String triggerExecutionId) {
        var condition = table().TRIGGER_EXECUTION_ID.eq(triggerExecutionId);
        return findAsync(tenantId, condition);
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public Flux<Execution> findAllByTrigger(TriggerId trigger) {
        var condition = table().TRIGGER_ID.eq(trigger.uid());
        return findAsync(trigger.getTenantId(), condition);
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public Optional<Execution> findLatestForStates(String tenantId, String namespace, String flowId, List<State.Type> states) {
        var condition = table().NAMESPACE.eq(namespace)
            .and(table().FLOW_ID.eq(flowId))
            .and(this.statesFilter(states));
        return findOne(tenantId, condition, table().START_DATE.desc());
    }

    @Override
    public Optional<Execution> findLatestForStatesWithoutAcl(String tenantId, String namespace, String flowId, List<State.Type> states) {
        var condition = table().NAMESPACE.eq(namespace)
            .and(table().FLOW_ID.eq(flowId))
            .and(this.statesFilter(states));
        return findOne(this.defaultFilterWithNoACL(tenantId), condition, table().START_DATE.desc());
    }

    @Override
    public List<String> findDistinctFieldValues(String tenantId, QueryFilter.Field field, List<QueryFilter> filters, Pageable pageable) {
        return findDistinctFieldValues(tenantId, field, filters, pageable, QueryFilter.Resource.EXECUTION);
    }

    @Override
    public Optional<Execution> findById(String tenantId, String id, boolean allowDeleted) {
        return findById(tenantId, id, allowDeleted, true);
    }

    @Override
    public Optional<Execution> findByIdWithoutAcl(String tenantId, String id) {
        return findById(tenantId, id, false, false);
    }

    @Override
    public Execution findByIdWithoutAcl(String id) {
        return findOne(DSL.noCondition(), table().KEY.eq(id)).orElse(null);
    }

    public Optional<Execution> findById(String tenantId, String id, boolean allowDeleted, boolean withAccessControl) {
        Condition defaultFilter = withAccessControl ? this.defaultFilter(tenantId, allowDeleted) : this.defaultFilterWithNoACL(tenantId, allowDeleted);
        Condition condition = table().KEY.eq(id);
        return findOne(defaultFilter, condition);
    }

    abstract protected Condition findCondition(String query, Map<String, String> labels);

    protected Condition findQueryCondition(String query) {
        return findCondition(query, Map.of());
    }

    abstract public Condition findLabelCondition(Either<Map<?, ?>, String> value, QueryFilter.Op operation);

    protected Condition statesFilter(List<State.Type> state) {
        return table().STATE_CURRENT
            .in(state.stream().map(value -> StateType.valueOf(value.name())).toList());
    }

    @Override
    public ArrayListTotal<Execution> find(
        Pageable pageable,
        @Nullable String tenantId,
        @Nullable List<QueryFilter> filters

    ) {
        return findPage(pageable, tenantId, this.computeFindCondition(filters, null));
    }

    @Override
    public ArrayListTotal<Execution> find(
        Pageable pageable,
        @Nullable String tenantId,
        @Nullable List<QueryFilter> filters,
        @Nullable DateFilter dateFilter) {
        return findPage(pageable, tenantId, this.computeFindCondition(filters, dateFilter));
    }

    @Override
    public Flux<Execution> find(
        @Nullable String query,
        @Nullable String tenantId,
        @Nullable List<FlowScope> scope,
        @Nullable String namespace,
        @Nullable String flowId,
        @Nullable ZonedDateTime startDate,
        @Nullable ZonedDateTime endDate,
        @Nullable List<State.Type> state,
        @Nullable Map<String, String> labels,
        @Nullable String triggerExecutionId,
        boolean deleted) {
        return Flux.create(
            emitter -> this.jdbcRepository
                .getDslContextWrapper()
                .transaction(configuration ->
                {
                    DSLContext context = DSL.using(configuration);

                    SelectConditionStep<Record1<JSONB>> select = this.findSelect(
                        context,
                        query,
                        tenantId,
                        scope,
                        namespace,
                        flowId,
                        startDate,
                        endDate,
                        state,
                        labels,
                        triggerExecutionId,
                        deleted
                    );

                    // fetchSize will fetch rows 100 by 100 even for databases where the driver loads all in memory
                    // using a stream will fetch lazily, otherwise all fetches would be done before starting emitting the items
                    try (var stream = select.fetchSize(FETCH_SIZE).stream()) {
                        stream.map(this.jdbcRepository::map).forEach(emitter::next);
                    } finally {
                        emitter.complete();
                    }
                }),
            FluxSink.OverflowStrategy.BUFFER
        );
    }

    private Condition computeFindCondition(@Nullable List<QueryFilter> filters, @Nullable DateFilter dateFilter) {
        boolean hasKindFilter = filters != null && filters.stream().anyMatch(AbstractJdbcExecutionRepository::containsLeafForKind);
        Condition dateFilterCondition = buildDateFilterCondition(filters, dateFilter);
        return hasKindFilter ? dateFilterCondition : dateFilterCondition.and(normalKindCondition());
    }

    private static boolean containsLeafForKind(QueryFilter filter) {
        if (filter.isLeaf()) {
            return filter.field() == QueryFilter.Field.KIND;
        }
        return filter.children().stream().anyMatch(AbstractJdbcExecutionRepository::containsLeafForKind);
    }

    @Override
    protected Field<?> getColumnField(QueryFilter.Field field) {
        if (field == QueryFilter.Field.TASK_ID) {
            return table().LOOP_RUN_TASK_ID;
        }
        return super.getColumnField(field);
    }

    private Condition buildDateFilterCondition(@Nullable List<QueryFilter> filters, @Nullable DateFilter dateFilter) {
        if (dateFilter == DateFilter.START_OR_END_DATE && filters != null) {
            List<QueryFilter> dateBoundaryFilters = filters.stream()
                .filter(f -> f.field() == QueryFilter.Field.START_DATE || f.field() == QueryFilter.Field.END_DATE)
                .toList();
            List<QueryFilter> otherFilters = filters.stream()
                .filter(f -> f.field() != QueryFilter.Field.START_DATE && f.field() != QueryFilter.Field.END_DATE)
                .toList();

            Condition onStartDate = this.filter(dateBoundaryFilters, table().START_DATE, Resource.EXECUTION);
            Condition onEndDate = this.filter(dateBoundaryFilters, table().END_DATE, Resource.EXECUTION);
            Condition dateOrCondition = dateBoundaryFilters.isEmpty() ? DSL.noCondition() : onStartDate.or(onEndDate);
            return dateOrCondition.and(this.filter(otherFilters, table().START_DATE, Resource.EXECUTION));
        }

        Field<?> dateColumn = dateFilter == DateFilter.END_DATE
            ? table().END_DATE
            : table().START_DATE;
        return this.filter(filters, dateColumn, Resource.EXECUTION);
    }

    private SelectConditionStep<Record1<JSONB>> findSelect(
        DSLContext context,
        @Nullable String query,
        @Nullable String tenantId,
        @Nullable List<FlowScope> scope,
        @Nullable String namespace,
        @Nullable String flowId,
        @Nullable ZonedDateTime startDate,
        @Nullable ZonedDateTime endDate,
        @Nullable List<State.Type> state,
        @Nullable Map<String, String> labels,
        @Nullable String triggerExecutionId,
        boolean deleted) {
        var select = context
            .select(
                table().VALUE
            )
            .from(table())
            .where(this.defaultFilter(tenantId, deleted));

        select = filteringQuery(select, scope, namespace, flowId, null, query, labels, triggerExecutionId);

        if (startDate != null) {
            select = select.and(DSL.condition("{0} >= {1}", table().START_DATE, DSL.val(startDate.toOffsetDateTime())));
        }

        if (endDate != null) {
            select = select.and(DSL.condition("{0} <= {1}", table().END_DATE, DSL.val(endDate.toOffsetDateTime())));
        }

        if (state != null) {
            select = select.and(this.statesFilter(state));
        }

        return select;
    }

    @Override
    public ArrayListTotal<Execution> findByFlowId(String tenantId, String namespace, String id, Pageable pageable) {
        var condition = table().NAMESPACE.eq(namespace).and(table().FLOW_ID.eq(id));
        return findPage(pageable, tenantId, condition);
    }

    @Override
    public Flux<Execution> findAsync(String tenantId, List<QueryFilter> filters) {
        // same condition as find(Pageable, String, List) so that streaming consumers see exactly what the search returns
        return findAsync(defaultFilter(tenantId), this.computeFindCondition(filters, null));
    }

    private <T extends Record> SelectConditionStep<T> filteringQuery(
        SelectConditionStep<T> select,
        @Nullable List<FlowScope> scope,
        @Nullable String namespace,
        @Nullable String flowId,
        @Nullable List<FlowFilter> flows,
        @Nullable String query,
        @Nullable Map<String, String> labels,
        @Nullable String triggerExecutionId) {
        if (scope != null && !scope.containsAll(Arrays.stream(FlowScope.values()).toList())) {
            if (scope.contains(FlowScope.USER)) {
                select = select.and(table().NAMESPACE.ne(systemFlowsConfiguration.namespace()));
            } else if (scope.contains(FlowScope.SYSTEM)) {
                select = select.and(table().NAMESPACE.eq(systemFlowsConfiguration.namespace()));
            }
        }

        if (namespace != null) {
            if (flowId != null) {
                select = select.and(table().NAMESPACE.eq(namespace));
            } else {
                select = select.and(DSL.or(table().NAMESPACE.eq(namespace), table().NAMESPACE.startsWith(namespace + ".")));
            }
        }

        if (flowId != null) {
            select = select.and(DSL.or(table().FLOW_ID.eq(flowId)));
        }

        if (query != null || labels != null) {
            select = select.and(this.findCondition(query, labels));
        }

        if (triggerExecutionId != null) {
            select = select.and(table().TRIGGER_EXECUTION_ID.eq(triggerExecutionId));
        }

        if (flows != null) {
            select = select.and(
                DSL.or(
                    flows
                        .stream()
                        .map(
                            e -> table().NAMESPACE.eq(e.getNamespace())
                                .and(table().FLOW_ID.eq(e.getId()))
                        )
                        .toList()
                )
            );
        }

        return select;
    }

    @Override
    public List<Execution> lastExecutions(
        String tenantId,
        @Nullable List<FlowFilter> flows) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                DSLContext context = DSL.using(configuration);

                Select<Record2<JSONB, Integer>> subquery = context
                    .select(
                        table().VALUE,
                        DSL.rowNumber().over(
                            DSL.partitionBy(
                                table().NAMESPACE,
                                table().FLOW_ID
                            ).orderBy(DSL.coalesce(table().END_DATE, table().START_DATE).desc())
                        ).as("row_num")
                    )
                    .from(table())
                    .where(this.defaultFilter(tenantId))
                    .and(normalKindCondition())
                    .and(
                        DSL.or(
                            ListUtils.emptyOnNull(flows).isEmpty() ? DSL.noCondition()
                                : DSL.or(
                                    flows.stream()
                                        .map(
                                            flow -> DSL.and(
                                                table().NAMESPACE.eq(flow.getNamespace()),
                                                table().FLOW_ID.eq(flow.getId())
                                            )
                                        )
                                        .toList()
                                )
                        )
                    );

                Table<Record2<JSONB, Integer>> cte = subquery.asTable("cte");

                SelectConditionStep<? extends Record1<?>> mainQuery = context
                    .select(cte.field(table().VALUE))
                    .from(cte)
                    .where(cte.field("row_num", Integer.class).eq(1));
                return mainQuery.fetch().map(this.jdbcRepository::map);
            });
    }

    @SneakyThrows
    @Override
    public Execution delete(Execution execution) {
        Optional<Execution> revision = this.findById(execution.getTenantId(), execution.getId());
        if (revision.isEmpty()) {
            throw new IllegalStateException("Execution " + execution.getId() + " doesn't exists");
        }

        Execution deleted = execution.toDeleted();

        Map<Field<?>, Object> fields = this.jdbcRepository.persistFields(deleted);
        this.jdbcRepository.persist(deleted, fields);

        eventPublisher.publishEvent(CrudEvent.delete(deleted));

        return deleted;
    }

    @Override
    public boolean purge(Execution execution) {
        boolean deleted = this.jdbcRepository.delete(execution) > 0;
        eventPublisher.publishEvent(CrudEvent.delete(execution));
        return deleted;
    }

    @Override
    public Integer purge(List<Execution> executions) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                DSLContext context = DSL.using(configuration);

                // we send the event before to be sure that if sending the event crash, we would not delete the exec
                executions.forEach(execution -> eventPublisher.publishEvent(CrudEvent.delete(execution)));

                return context.delete(table())
                    .where(table().KEY.in(executions.stream().map(Execution::getId).toList()))
                    .execute();
            });
    }

    @Override
    public Optional<ExecutorContext> lock(String executionId, Function<Execution, ExecutorContext> function) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                DSLContext context = DSL.using(configuration);

                SelectForUpdateOfStep<Record1<JSONB>> from = context
                    .select(table().VALUE)
                    .from(table())
                    .where(table().KEY.eq(executionId))
                    .and(this.defaultFilter())
                    .forUpdate();

                Optional<Execution> execution = this.jdbcRepository.fetchOne(from);

                // not ready for now, skip and wait for a first state
                if (execution.isEmpty()) {
                    return Optional.empty();
                }

                ExecutorContext executor = function.apply(execution.get());

                if (executor != null) {
                    if (executor.getExecution().getId().equals(executionId)) {
                        // same execution: we use UPDATE as it's more performant than persist/upsert
                        this.jdbcRepository.update(executor.getExecution(), context, null);
                    } else {
                        // different execution ID: this is possible for ex for replay, we must INSERT via persist/upsert
                        this.jdbcRepository.persist(executor.getExecution(), context, null);
                    }
                    return Optional.of(executor);
                }

                return Optional.empty();
            });
    }

    @Override
    public Function<String, String> sortMapping() throws IllegalArgumentException {
        Map<String, String> mapper = Map.of(
            "id", table().ID.getName(),
            Execution.STATE_START_DATE_FIELD, table().START_DATE.getName(),
            Execution.STATE_END_DATE_FIELD, table().END_DATE.getName(),
            "state.duration", table().STATE_DURATION.getName(),
            "namespace", table().NAMESPACE.getName(),
            "flowId", table().FLOW_ID.getName(),
            "state.current", table().STATE_CURRENT.getName(),
            "loopRunIndex", table().LOOP_RUN_INDEX.getName()
        );

        return mapper::get;
    }

}
