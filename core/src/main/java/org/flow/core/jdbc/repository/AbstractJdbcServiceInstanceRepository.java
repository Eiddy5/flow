package org.flow.core.jdbc.repository;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.jooq.Condition;
import org.jooq.Configuration;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record1;
import org.jooq.SelectConditionStep;
import org.jooq.impl.DSL;

import org.flow.gen.flow.Tables;
import org.flow.gen.flow.tables.ServiceInstanceTable;
import org.flow.core.exceptions.TypeConversionException;
import org.flow.core.models.QueryFilter;
import org.flow.core.repositories.ArrayListTotal;
import org.flow.core.repositories.ServiceInstanceRepositoryInterface;
import org.flow.core.runners.TransactionContext;
import org.flow.core.server.Service;
import org.flow.core.server.ServiceInstance;
import org.flow.core.server.ServiceLivenessStore;
import org.flow.core.server.ServiceStateTransition;
import org.flow.core.server.ServiceType;
import org.flow.core.utils.TypeConverter;
import org.flow.core.jdbc.runner.JdbcTransactionContext;

import io.micronaut.data.model.Pageable;
import jakarta.annotation.Nullable;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import static org.jooq.impl.DSL.using;

@Getter
@Slf4j
public abstract class AbstractJdbcServiceInstanceRepository extends AbstractJdbcRepository implements ServiceInstanceRepositoryInterface, ServiceLivenessStore {

    protected org.flow.core.jdbc.AbstractJdbcRepository<ServiceInstance> jdbcRepository;

    public AbstractJdbcServiceInstanceRepository(final org.flow.core.jdbc.AbstractJdbcRepository<ServiceInstance> jdbcRepository) {
        this.jdbcRepository = jdbcRepository;
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public Optional<ServiceInstance> findById(final String id) {
        return jdbcRepository.getDslContextWrapper().transactionResult(
            configuration -> findById(id, DSL.using(configuration), false)
        );
    }

    private Optional<ServiceInstance> findById(final String id,
        final DSLContext dslContext,
        final boolean isForUpdate) {

        SelectConditionStep<Record1<JSONB>> query = dslContext
            .select(table().VALUE)
            .from(table())
            .where(table().SERVICE_ID.eq(id));

        return isForUpdate ? this.jdbcRepository.fetchOne(query.forUpdate()) : this.jdbcRepository.fetchOne(query);
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public List<ServiceInstance> findAllInstancesInStates(final Set<Service.ServiceState> states) {
        return this.jdbcRepository.getDslContextWrapper()
            .transactionResult(configuration -> findAllInstancesInStates(configuration, states, false));
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public List<ServiceInstance> findAllInstancesInState(final Service.ServiceState state) {
        return this.jdbcRepository.getDslContextWrapper()
            .transactionResult(configuration ->
            {
                var query = using(configuration)
                    .select(table().VALUE)
                    .from(table())
                    .where(table().STATE.eq(state.name()));
                return this.jdbcRepository.fetch(query);
            });
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public List<ServiceInstance> findAllInstancesBetween(final ServiceType type, final Instant from, final Instant to) {
        return jdbcRepository.getDslContextWrapper().transactionResult(configuration ->
        {
            SelectConditionStep<Record1<JSONB>> query = using(configuration)
                .select(table().VALUE)
                .from(table())
                .where(table().SERVICE_TYPE.eq(type.name()))
                .and(table().CREATED_AT.lt(to.atOffset(ZoneOffset.UTC)))
                .and(table().UPDATED_AT.ge(from.atOffset(ZoneOffset.UTC)));

            return this.jdbcRepository.fetch(query);
        });
    }

    public List<ServiceInstance> findAllInstancesInStates(final Configuration configuration,
        final Set<Service.ServiceState> states,
        final boolean isForUpdate) {
        SelectConditionStep<Record1<JSONB>> query = using(configuration)
            .select(table().VALUE)
            .from(table())
            .where(table().STATE.in(states.stream().map(Enum::name).toList()));

        return isForUpdate ? this.jdbcRepository.fetch(query.forUpdate().skipLocked()) : this.jdbcRepository.fetch(query);
    }

    @Override
    public void processAllNonRunningInstances(BiConsumer<TransactionContext, ServiceInstance> consumer) {
        jdbcRepository.getDslContextWrapper().transaction(configuration ->
        {
            var dslContext = DSL.using(configuration);
            var query = dslContext
                .select(table().VALUE)
                .from(table())
                .where(table().STATE.notIn(Service.ServiceState.CREATED.name(), Service.ServiceState.RUNNING.name(), Service.ServiceState.INACTIVE.name()));

            var txContext = new JdbcTransactionContext(dslContext);
            this.jdbcRepository.fetch(query.forUpdate().skipLocked()).forEach(instance -> consumer.accept(txContext, instance));
        });
    }

    @Override
    public void processInstanceInStates(Set<Service.ServiceState> states, BiConsumer<TransactionContext, ServiceInstance> consumer) {
        jdbcRepository.getDslContextWrapper().transaction(configuration ->
        {
            var dslContext = DSL.using(configuration);
            var query = dslContext
                .select(table().VALUE)
                .from(table())
                .where(table().STATE.in(states.stream().map(Enum::name).toList()));

            var txContext = new JdbcTransactionContext(dslContext);
            this.jdbcRepository.fetch(query.forUpdate().skipLocked()).forEach(instance -> consumer.accept(txContext, instance));
        });
    }

    /**
     * Finds all service instances which are {@link Service.ServiceState#NOT_RUNNING}.
     *
     * @return the list of {@link ServiceInstance}.
     */
    public List<ServiceInstance> findAllInstancesInNotRunningState() {
        return jdbcRepository.getDslContextWrapper().transactionResult(
            configuration -> findAllInstancesInNotRunningState(configuration, false)
        );
    }

    /**
     * Finds all service instances which are {@link Service.ServiceState#NOT_RUNNING}.
     *
     * @return the list of {@link ServiceInstance}.
     */
    public List<ServiceInstance> findAllInstancesInNotRunningState(final Configuration configuration,
        final boolean isForUpdate) {
        SelectConditionStep<Record1<JSONB>> query = using(configuration)
            .select(table().VALUE)
            .from(table())
            .where(table().STATE.eq(Service.ServiceState.NOT_RUNNING.name()));

        return isForUpdate ? this.jdbcRepository.fetch(query.forUpdate().skipLocked()) : this.jdbcRepository.fetch(query);
    }

    @Override
    public int purgeEmptyInstances(Instant until) {
        return jdbcRepository.getDslContextWrapper().transactionResult(
            configuration -> using(configuration).delete(table())
                .where(table().STATE.in(Service.ServiceState.INACTIVE.name(), "EMPTY"))
                .and(table().UPDATED_AT.lessOrEqual(until.atOffset(ZoneOffset.UTC)))
                .execute()
        );
    }

    public void delete(DSLContext context, ServiceInstance instance) {
        this.jdbcRepository.delete(context, instance);
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public void delete(final ServiceInstance instance) {
        this.jdbcRepository.delete(instance);
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public ServiceInstance save(final ServiceInstance instance) {
        this.jdbcRepository.persist(instance, this.jdbcRepository.persistFields(instance));
        return instance;
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public List<ServiceInstance> findAll() {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(
                configuration -> this.jdbcRepository.fetch(
                    using(configuration).select(table().VALUE).from(table())
                )
            );
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public ArrayListTotal<ServiceInstance> find(final Pageable pageable,
        @Nullable final List<QueryFilter> filters) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                DSLContext context = using(configuration);
                SelectConditionStep<Record1<JSONB>> select = context.select(table().VALUE).from(table()).where("1=1");
                if (filters != null && !filters.isEmpty()) {
                    select = select.and(
                        this.filter(filters, table().CREATED_AT, QueryFilter.Resource.SERVICE_INSTANCE)
                    );
                }
                return this.jdbcRepository.fetchPage(context, select, pageable);
            });
    }

    /** {@inheritDoc} **/
    @Override
    protected Condition filter(List<QueryFilter> filters, Field<?> dateColumn, QueryFilter.Resource resource) {
        return super.filter(ServiceInstanceRepositoryInterface.expandStateNamesForFilterQueryBackwardCompat(filters), dateColumn, resource);
    }

    /** {@inheritDoc} **/
    @Override
    protected Field<?> getColumnField(QueryFilter.Field field) {
        if (field == QueryFilter.Field.TYPE) {
            // The service type column is named "service_type", not "type"
            return table().SERVICE_TYPE;
        }
        return super.getColumnField(field);
    }

    /** {@inheritDoc} **/
    @Override
    protected Condition createdCondition(Object value, QueryFilter.Op operation, @Nullable Field<?> dateColumn) {
        Field<?> column = dateColumn != null ? dateColumn : table().CREATED_AT;
        // Accept ISO-8601 durations (e.g. PT5M) as a "now minus duration" threshold;
        // the supplied operation is applied against that threshold.
        try {
            Duration duration = TypeConverter.toDuration(value);
            ZonedDateTime threshold = ZonedDateTime.now().minus(duration);
            return applyDateCondition(threshold.toOffsetDateTime(), operation, column);
        } catch (TypeConversionException ignored) {
            // Not a duration — fall through to absolute date parsing
        }
        return super.createdCondition(value, operation, column);
    }

    @Override
    protected Condition generateStateCondition(Object value, QueryFilter.Op operation) {
        List<String> stateNames = switch (value) {
            case List<?> list -> list.stream().map(Object::toString).toList();
            case String s -> List.of(s);
            default -> throw new org.flow.core.exceptions.InvalidQueryFiltersException(
                "Field 'state' requires a String or List<String> value for service instances"
            );
        };
        Field<String> stateField = table().STATE;
        return switch (operation) {
            case EQUALS, IN -> stateField.in(stateNames);
            case NOT_EQUALS, NOT_IN -> stateField.notIn(stateNames);
            default -> throw new org.flow.core.exceptions.InvalidQueryFiltersException(
                "Unsupported operation for STATE: " + operation
            );
        };
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public ServiceStateTransition.Response mayTransitServiceTo(final TransactionContext txContext,
        final ServiceInstance instance,
        final Service.ServiceState newState,
        final String reason) {
        ImmutablePair<ServiceInstance, ServiceInstance> result = mayUpdateStatusById(
            txContext,
            instance,
            newState,
            reason
        );
        return ServiceStateTransition.logTransitionAndGetResponse(instance, newState, result);
    }

    /**
     * Attempt to transition the state of a given service to given new state.
     * This method may not update the service if the transition is not valid.
     *
     * @param instance the new service instance.
     * @param newState the new state of the service.
     * @return an {@link Optional} of {@link ImmutablePair} holding the old (left), and new {@link ServiceInstance} or {@code null} if transition failed (right).
     *         Otherwise, an {@link Optional#empty()} if the no service can be found.
     */
    private ImmutablePair<ServiceInstance, ServiceInstance> mayUpdateStatusById(final TransactionContext txContext,
        final ServiceInstance instance,
        final Service.ServiceState newState,
        final String reason) {
        // Find the ServiceInstance to be updated
        var dslContext = txContext.unwrap(JdbcTransactionContext.class).getDslContext();
        Optional<ServiceInstance> optional = findById(instance.uid(), dslContext, true);

        // Check whether service was found.
        if (optional.isEmpty()) {
            return null;
        }

        // Check whether the status transition is valid before saving.
        final ServiceInstance before = optional.get();
        if (before.state().isValidTransition(newState)) {
            ServiceInstance updated = before
                .state(newState, Instant.now(), reason)
                .server(instance.server())
                .metrics(instance.metrics());
            // Synchronize
            save(updated);
            return new ImmutablePair<>(before, updated);
        }
        return new ImmutablePair<>(before, null);
    }

    @Override
    protected ServiceInstanceTable table() {
        return jdbcRepository.getTable(Tables.SERVICE_INSTANCE);
    }

    /** {@inheritDoc} **/
    @Override
    public Function<String, String> sortMapping() {
        Map<String, String> mapper = Map.of(
            "createdAt", table().CREATED_AT.getName(),
            "updatedAt", table().UPDATED_AT.getName(),
            "serviceId", table().SERVICE_ID.getName()
        );
        return mapper::get;
    }
}
