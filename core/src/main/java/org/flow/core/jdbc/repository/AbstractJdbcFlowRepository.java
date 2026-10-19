package org.flow.core.jdbc.repository;

import org.flow.core.models.flows.Flow;
import org.flow.core.models.flows.FlowForExecution;
import org.flow.core.models.flows.FlowInterface;
import org.flow.core.models.flows.FlowWithException;
import org.flow.core.models.flows.FlowWithSource;
import org.flow.core.models.flows.GenericFlow;
import org.flow.core.models.flows.SourceSearchScope;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

import org.jooq.*;
import org.jooq.Record;
import org.jooq.impl.DSL;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;

import org.flow.gen.flow.Tables;
import org.flow.core.events.CrudEvent;
import org.flow.core.events.CrudEventType;
import org.flow.core.exceptions.AlreadyExistsException;
import org.flow.core.exceptions.DeserializationException;
import org.flow.core.exceptions.FlowProcessingException;
import org.flow.core.models.QueryFilter;
import org.flow.core.models.QueryFilter.Resource;
import org.flow.core.models.SearchResult;
import org.flow.core.models.SourceMatch;
import org.flow.core.models.flows.*;
import org.flow.core.models.validations.ModelValidator;
import org.flow.core.queues.QueueException;
import org.flow.core.repositories.ArrayListTotal;
import org.flow.core.repositories.FlowRepositoryInterface;
import org.flow.core.services.FlowParsingService;
import org.flow.core.utils.Either;
import org.flow.core.serializers.JacksonMapper;
import org.flow.core.utils.ListUtils;
import org.flow.core.utils.SourceSearchMatcher;

import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.data.model.Pageable;
import jakarta.annotation.Nullable;
import jakarta.validation.ConstraintViolationException;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

@Slf4j
public abstract class AbstractJdbcFlowRepository extends AbstractJdbcRepository implements FlowRepositoryInterface {

    protected static final ObjectMapper MAPPER = JacksonMapper.ofJson();

    @Override
    protected org.flow.gen.flow.tables.FlowsTable table() {
        return jdbcRepository.getTable(Tables.FLOWS);
    }

    private final ApplicationEventPublisher<CrudEvent<FlowInterface>> eventPublisher;
    private final ModelValidator modelValidator;
    private final FlowParsingService flowParsingService;

    protected org.flow.core.jdbc.AbstractJdbcRepository<FlowInterface> jdbcRepository;

    @SuppressWarnings("unchecked")
    public AbstractJdbcFlowRepository(
        org.flow.core.jdbc.AbstractJdbcRepository<FlowInterface> jdbcRepository,
        ModelValidator modelValidator,
        ApplicationEventPublisher<CrudEvent<FlowInterface>> eventPublisher,
        FlowParsingService flowParsingService) {
        this.jdbcRepository = jdbcRepository;
        this.modelValidator = modelValidator;
        this.eventPublisher = eventPublisher;
        this.flowParsingService = flowParsingService;
        this.jdbcRepository.setDeserializer(record ->
        {
            String source = record.get(table().VALUE).data();
            String namespace = record.get(table().NAMESPACE);
            String tenantId = record.get(table().TENANT_ID);
            try {
                Map<String, Object> map = MAPPER.readValue(source, new TypeReference<>() {
                });

                // Inject default plugin 'version' props before converting
                // to flow to correctly resolve to plugin type.
                map = flowParsingService.injectPluginVersions(tenantId, namespace, map);

                Flow deserialize = MAPPER.convertValue(map, Flow.class);

                // raise exception for invalid flow, ex: Templates disabled
                deserialize.allTasksWithChilds();

                return deserialize;
            } catch (DeserializationException | IOException | IllegalArgumentException | FlowProcessingException e) {
                try {
                    JsonNode jsonNode = JacksonMapper.ofJson().readTree(source);
                    return FlowWithException.from(jsonNode, e)
                        .orElseThrow(() -> e instanceof DeserializationException de ? de : new DeserializationException(e, source));
                } catch (JsonProcessingException ex) {
                    throw new DeserializationException(ex, source);
                }
            }
        });
    }

    @Override
    public Optional<Flow> findById(String tenantId, String namespace, String id, Optional<Integer> revision, Boolean allowDeleted) {
        return jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                DSLContext context = DSL.using(configuration);

                var from = revision.map(
                    integer -> context
                        .select(table().VALUE, table().NAMESPACE, table().TENANT_ID)
                        .from(jdbcRepository.getTable())
                        .where(this.defaultFilter(tenantId, true))
                        .and(table().NAMESPACE.eq(namespace))
                        .and(table().ID.eq(id))
                        .and(
                            table().REVISION.eq(integer)
                        )
                ).orElseGet(
                    () -> context
                        .select(table().VALUE, table().NAMESPACE, table().TENANT_ID)
                        .from(fromLastRevision())
                        .where(this.defaultFilter(tenantId, Boolean.TRUE.equals(allowDeleted)))
                        .and(table().NAMESPACE.eq(namespace))
                        .and(table().ID.eq(id))
                );

                return this.jdbcRepository.fetchOne(from).map(it -> (Flow) it);
            });
    }

    @Override
    public Optional<Flow> findByIdWithoutAcl(String tenantId, String namespace, String id, Optional<Integer> revision) {
        return jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                DSLContext context = DSL.using(configuration);

                var from = revision
                    .map(
                        integer -> context
                            .select(table().VALUE, table().NAMESPACE, table().TENANT_ID)
                            .from(jdbcRepository.getTable())
                            .where(this.defaultFilterWithNoACL(tenantId, true))
                            .and(table().NAMESPACE.eq(namespace))
                            .and(table().ID.eq(id))
                            .and(table().REVISION.eq(integer))
                    ).orElseGet(
                        () -> context
                            .select(table().VALUE, table().NAMESPACE, table().TENANT_ID)
                            .from(fromLastRevision())
                            .where(this.defaultFilterWithNoACL(tenantId, true))
                            .and(table().NAMESPACE.eq(namespace))
                            .and(table().ID.eq(id))
                    );

                return this.jdbcRepository.fetchOne(from).map(it -> (Flow) it);
            });
    }

    protected Table<Record> fromLastRevision() {
        return JdbcFlowRepositoryService.lastRevision(jdbcRepository);
    }

    protected Table<Record> fromLastNonDraftRevision() {
        return JdbcFlowRepositoryService.lastNonDraftRevision(jdbcRepository);
    }

    protected Condition noAclDefaultFilter(String tenantId) {
        return buildTenantCondition(tenantId);
    }

    @Override
    protected Condition defaultFilter(String tenantId, boolean allowDeleted) {
        return super.defaultFilter(tenantId, allowDeleted).and(aclCondition(Resource.FLOW));
    }

    // "executable" filtering stays independent of read-ACL and is scoped by the EXECUTION grant instead,
    // since users with execute-but-not-read permission are exactly who these two methods serve.
    protected Condition defaultExecutionFilter(String tenantId) {
        return this.defaultFilterWithNoACL(tenantId).and(table().DISABLED.eq(false)).and(aclCondition(Resource.EXECUTION));
    }

    @Override
    public Optional<FlowWithSource> findByIdWithSource(String tenantId, String namespace, String id, Optional<Integer> revision, Boolean allowDeleted) {
        return jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                DSLContext context = DSL.using(configuration);

                var from = revision.map(
                    integer -> context
                        .select(
                            table().SOURCE_CODE,
                            table().VALUE,
                            table().NAMESPACE,
                            table().TENANT_ID
                        )
                        .from(jdbcRepository.getTable())
                        .where(this.defaultFilter(tenantId, true))
                        .and(table().NAMESPACE.eq(namespace))
                        .and(table().ID.eq(id))
                        .and(table().REVISION.eq(integer))
                )
                    .orElseGet(
                        () -> context
                            .select(
                                table().SOURCE_CODE,
                                table().VALUE,
                                table().NAMESPACE,
                                table().TENANT_ID
                            )
                            .from(fromLastRevision())
                            .where(this.defaultFilter(tenantId, Boolean.TRUE.equals(allowDeleted)))
                            .and(table().NAMESPACE.eq(namespace))
                            .and(table().ID.eq(id))
                    );

                Record4<String, JSONB, String, String> fetched = from.fetchAny();

                if (fetched == null) {
                    return Optional.empty();
                }

                Flow flow = (Flow) jdbcRepository.map(fetched);
                String source = fetched.get(table().SOURCE_CODE);
                if (flow instanceof FlowWithException fwe) {
                    return Optional.of(fwe.toBuilder().source(source).build());
                }
                return Optional.of(FlowWithSource.of(flow, source));
            });
    }

    @Override
    public Optional<FlowWithSource> findByIdWithSourceWithoutAcl(String tenantId, String namespace, String id, Optional<Integer> revision) {
        return jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                DSLContext context = DSL.using(configuration);

                var from = revision.map(
                    integer -> context
                        .select(table().SOURCE_CODE, table().VALUE, table().NAMESPACE, table().TENANT_ID)
                        .from(jdbcRepository.getTable())
                        .where(this.defaultFilterWithNoACL(tenantId, true))
                        .and(table().NAMESPACE.eq(namespace))
                        .and(table().ID.eq(id))
                        .and(table().REVISION.eq(integer))
                )
                    .orElseGet(
                        () -> context
                            .select(table().SOURCE_CODE, table().VALUE, table().NAMESPACE, table().TENANT_ID)
                            .from(fromLastRevision())
                            .where(this.defaultFilterWithNoACL(tenantId, true))
                            .and(table().NAMESPACE.eq(namespace))
                            .and(table().ID.eq(id))
                    );
                Record4<String, JSONB, String, String> fetched = from.fetchAny();

                if (fetched == null) {
                    return Optional.empty();
                }

                Flow flow = (Flow) jdbcRepository.map(fetched);
                String source = fetched.get(table().SOURCE_CODE);
                if (flow instanceof FlowWithException fwe) {
                    return Optional.of(fwe.toBuilder().source(source).build());
                }
                return Optional.of(FlowWithSource.of(flow, source));
            });
    }

    @Override
    public Optional<Flow> findByIdForExecution(String tenantId, String namespace, String id) {
        return findByIdForExecution(tenantId, namespace, id, this.defaultFilter(tenantId));
    }

    @Override
    public Optional<Flow> findByIdForExecutionWithoutAcl(String tenantId, String namespace, String id) {
        return findByIdForExecution(tenantId, namespace, id, this.defaultFilterWithNoACL(tenantId));
    }

    private Optional<Flow> findByIdForExecution(String tenantId, String namespace, String id, Condition tenantCondition) {
        return jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                DSLContext context = DSL.using(configuration);

                var select = context
                    .select(table().VALUE, table().NAMESPACE, table().TENANT_ID)
                    .from(fromLastNonDraftRevision())
                    .where(tenantCondition)
                    .and(table().NAMESPACE.eq(namespace))
                    .and(table().ID.eq(id));

                return this.jdbcRepository.fetchOne(select).map(it -> (Flow) it);
            });
    }

    @Override
    public Optional<FlowWithSource> findByIdWithSourceForExecution(String tenantId, String namespace, String id) {
        return findByIdWithSourceForExecution(tenantId, namespace, id, this.defaultFilter(tenantId));
    }

    @Override
    public Optional<FlowWithSource> findByIdWithSourceForExecutionWithoutAcl(String tenantId, String namespace, String id) {
        return findByIdWithSourceForExecution(tenantId, namespace, id, this.defaultFilterWithNoACL(tenantId));
    }

    private Optional<FlowWithSource> findByIdWithSourceForExecution(String tenantId, String namespace, String id, Condition tenantCondition) {
        return jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                DSLContext context = DSL.using(configuration);

                var select = context
                    .select(table().SOURCE_CODE, table().VALUE, table().NAMESPACE, table().TENANT_ID)
                    .from(fromLastNonDraftRevision())
                    .where(tenantCondition)
                    .and(table().NAMESPACE.eq(namespace))
                    .and(table().ID.eq(id));

                Record4<String, JSONB, String, String> fetched = select.fetchAny();

                if (fetched == null) {
                    return Optional.empty();
                }

                Flow flow = (Flow) jdbcRepository.map(fetched);
                String source = fetched.get(table().SOURCE_CODE);
                if (flow instanceof FlowWithException fwe) {
                    return Optional.of(fwe.toBuilder().source(source).build());
                }
                return Optional.of(FlowWithSource.of(flow, source));
            });
    }

    @Override
    public List<FlowWithSource> findAllWithSourceForExecutionForAllTenants() {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                var select = DSL
                    .using(configuration)
                    .select(
                        table().VALUE,
                        table().SOURCE_CODE,
                        table().NAMESPACE,
                        table().TENANT_ID
                    )
                    .from(fromLastNonDraftRevision())
                    .where(this.defaultFilter());

                // Same robust deserialization as findAllWithSourceForAllTenants(): we don't want
                // a single broken plugin in the JSON to crash scheduler bootstrap.
                return select.fetch().stream().map(record ->
                {
                    try {
                        return FlowWithSource.of((Flow) jdbcRepository.map(record), record.get(Tables.FLOWS.SOURCE_CODE.getName(), String.class));
                    } catch (Exception e) {
                        log.error("Unable to load the following flow:\n{}", record.get(table().VALUE).data(), e);
                        return null;
                    }
                }).filter(Objects::nonNull).toList();
            });
    }

    @Override
    public List<FlowWithSource> findRevisions(String tenantId, String namespace, String id, Boolean allowDeleted) {
        return findRevisions(tenantId, namespace, id, allowDeleted, null);
    }

    @Override
    public List<FlowWithSource> findRevisions(String tenantId, String namespace, String id, Boolean allowDeleted, List<Integer> revisions) {
        return findRevisions(namespace, id, revisions, this.defaultFilter(tenantId, Boolean.TRUE.equals(allowDeleted)));
    }

    @Override
    public List<FlowWithSource> findRevisionsWithoutAcl(String tenantId, String namespace, String id, Boolean allowDeleted, List<Integer> revisions) {
        return findRevisions(namespace, id, revisions, this.defaultFilterWithNoACL(tenantId, Boolean.TRUE.equals(allowDeleted)));
    }

    private List<FlowWithSource> findRevisions(String namespace, String id, List<Integer> revisions, Condition baseFilter) {
        return jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                Condition tenantAndRevisionCondition = baseFilter;
                if (!ListUtils.isEmpty(revisions)) {
                    tenantAndRevisionCondition = tenantAndRevisionCondition.and(table().REVISION.in(revisions));
                }
                Select<Record4<String, JSONB, String, String>> select = DSL
                    .using(configuration)
                    .select(table().SOURCE_CODE, table().VALUE, table().NAMESPACE, table().TENANT_ID)
                    .from(jdbcRepository.getTable())
                    .where(tenantAndRevisionCondition)
                    .and(table().NAMESPACE.eq(namespace))
                    .and(table().ID.eq(id))
                    .orderBy(table().REVISION.asc());

                return select.fetch()
                    .map(record -> FlowWithSource.of((Flow) jdbcRepository.map(record), record.get(table().SOURCE_CODE)));
            });
    }

    @Override
    public int count(String tenantId) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(
                configuration -> DSL
                    .using(configuration)
                    .selectCount()
                    .from(fromLastRevision())
                    .where(this.defaultFilter(tenantId))
                    .fetchOne(0, int.class)
            );
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public List<Flow> findAll(String tenantId) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                var select = DSL
                    .using(configuration)
                    .select(
                        table().VALUE,
                        table().NAMESPACE,
                        table().TENANT_ID
                    )
                    .from(fromLastRevision())
                    .where(this.defaultFilter(tenantId));

                return (List) this.jdbcRepository.fetch(select);
            });
    }

    @Override
    public List<Flow> findAllForAllTenants() {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                var select = DSL
                    .using(configuration)
                    .select(
                        table().VALUE,
                        table().NAMESPACE,
                        table().TENANT_ID
                    )
                    .from(fromLastRevision())
                    .where(this.defaultFilter());

                // findAllForAllTenants() is used in the backend, so we want it to work even if messy plugins exist.
                // That's why we will try to deserialize each flow and log an error but not crash in case of exception.
                List<Flow> flows = new ArrayList<>();
                select.fetch().forEach(
                    item ->
                    {
                        try {
                            Flow flow = (Flow) this.jdbcRepository.map(item);
                            flows.add(flow);
                        } catch (Exception e) {
                            log.error("Unable to load the following flow:\n{}", item.get(Tables.FLOWS.VALUE.getName(), String.class), e);
                        }
                    }
                );
                return flows;
            });
    }

    @Override
    public List<FlowWithSource> findAllWithSource(String tenantId) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                var select = DSL
                    .using(configuration)
                    .select(
                        table().VALUE,
                        table().SOURCE_CODE,
                        table().NAMESPACE,
                        table().TENANT_ID
                    )
                    .from(fromLastRevision())
                    .where(this.defaultFilter(tenantId));

                return select.fetch().map(
                    record -> FlowWithSource.of(
                        (Flow) jdbcRepository.map(record),
                        record.get(table().SOURCE_CODE)
                    )
                );
            });
    }

    @Override
    public List<FlowWithSource> findAllWithSourceWithNoAcl(String tenantId) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                var select = DSL
                    .using(configuration)
                    .select(
                        table().VALUE,
                        table().SOURCE_CODE,
                        table().NAMESPACE,
                        table().TENANT_ID
                    )
                    .from(fromLastRevision())
                    .where(this.noAclDefaultFilter(tenantId));

                return select.fetch().map(
                    record -> FlowWithSource.of(
                        (Flow) jdbcRepository.map(record),
                        record.get(table().SOURCE_CODE)
                    )
                );
            });
    }

    @Override
    public List<FlowWithSource> findAllWithSourceForAllTenants() {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                var select = DSL
                    .using(configuration)
                    .select(
                        table().VALUE,
                        table().SOURCE_CODE,
                        table().NAMESPACE,
                        table().TENANT_ID
                    )
                    .from(fromLastRevision())
                    .where(this.defaultFilter());

                // findAllWithSourceForAllTenants() is used in the backend, so we want it to work even if messy plugins exist.
                // That's why we will try to deserialize each flow and log an error but not crash in case of exception.
                return select.fetch().stream().map(record ->
                {
                    try {
                        return FlowWithSource.of((Flow) jdbcRepository.map(record), record.get(Tables.FLOWS.SOURCE_CODE.getName(), String.class));
                    } catch (Exception e) {
                        log.error("Unable to load the following flow:\n{}", record.get(table().VALUE).data(), e);
                        return null;
                    }
                }).filter(Objects::nonNull).toList();
            });
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public List<Flow> findByNamespace(String tenantId, String namespace) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                SelectConditionStep<Record3<JSONB, String, String>> select = findByNamespaceSelect(namespace)
                    .and(this.defaultFilter(tenantId));

                return (List) this.jdbcRepository.fetch(select);
            });
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public List<Flow> findByNamespacePrefix(String tenantId, String namespacePrefix) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                SelectConditionStep<Record3<JSONB, String, String>> select = findByNamespacePrefixSelect(namespacePrefix)
                    .and(this.defaultFilter(tenantId));

                return (List) this.jdbcRepository.fetch(select);
            });
    }

    @Override
    public List<FlowForExecution> findByNamespaceExecutable(String tenantId, String namespace) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                SelectConditionStep<Record3<JSONB, String, String>> select = findByNamespaceSelect(namespace)
                    .and(this.defaultExecutionFilter(tenantId));

                return this.jdbcRepository.fetch(select);
            }).stream().map(it -> (Flow) it).map(FlowForExecution::of).toList();
    }

    private SelectConditionStep<Record3<JSONB, String, String>> findByNamespaceSelect(String namespace) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(
                configuration -> DSL
                    .using(configuration)
                    .select(table().VALUE, table().NAMESPACE, table().TENANT_ID)
                    .from(fromLastRevision())
                    .where(table().NAMESPACE.eq(namespace))
            );
    }

    private SelectConditionStep<Record3<JSONB, String, String>> findByNamespacePrefixSelect(String namespacePrefix) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(
                configuration -> DSL
                    .using(configuration)
                    .select(table().VALUE, table().NAMESPACE, table().TENANT_ID)
                    .from(fromLastRevision())
                    .where(table().NAMESPACE.eq(namespacePrefix).or(table().NAMESPACE.startsWith(namespacePrefix + ".")))
            );
    }

    @Override
    public List<FlowWithSource> findByNamespaceWithSource(String tenantId, String namespace) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                var select = DSL
                    .using(configuration)
                    .select(
                        table().SOURCE_CODE,
                        table().VALUE,
                        table().NAMESPACE,
                        table().TENANT_ID
                    )
                    .from(fromLastRevision())
                    .where(table().NAMESPACE.eq(namespace))
                    .and(this.defaultFilter(tenantId));

                return select.fetch().map(
                    record -> FlowWithSource.of(
                        (Flow) jdbcRepository.map(record),
                        record.get(table().SOURCE_CODE)
                    )
                );
            });
    }

    @Override
    public List<FlowWithSource> findByNamespacePrefixWithSource(String tenantId, String namespacePrefix) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                var select = DSL
                    .using(configuration)
                    .select(
                        table().SOURCE_CODE,
                        table().VALUE,
                        table().NAMESPACE,
                        table().TENANT_ID
                    )
                    .from(fromLastRevision())
                    .where(DSL.or(table().NAMESPACE.eq(namespacePrefix), table().NAMESPACE.startsWith(namespacePrefix + ".")))
                    .and(this.defaultFilter(tenantId));

                return select.fetch().map(
                    record -> FlowWithSource.of(
                        (Flow) jdbcRepository.map(record),
                        record.get(table().SOURCE_CODE)
                    )
                );
            });
    }

    private <R extends Record, E> SelectConditionStep<R> fullTextSelect(String tenantId, DSLContext context, List<? extends Field<?>> field) {
        return fullTextSelect(tenantId, context, field, false);
    }

    /**
     * @param excludeDraft when true, resolves each flow to its most recent non-draft revision
     *                     instead of its latest revision.
     */
    @SuppressWarnings("unchecked")
    private <R extends Record, E> SelectConditionStep<R> fullTextSelect(String tenantId, DSLContext context, List<? extends Field<?>> field, boolean excludeDraft) {
        ArrayList<Field<?>> fields = new ArrayList<>();
        // add mandatory fields
        fields.add(table().VALUE);
        fields.add(table().TENANT_ID);
        fields.add(table().NAMESPACE);

        if (field != null) {
            fields.addAll(field);
        }

        return (SelectConditionStep<R>) context
            .select(fields)
            .from(excludeDraft ? fromLastNonDraftRevision() : fromLastRevision())
            .where(this.defaultFilter(tenantId));
    }

    abstract protected Condition findCondition(String query, Map<String, String> labels);

    protected Condition findQueryCondition(String query) {
        return findCondition(query, Map.of());
    }

    abstract protected Condition findCondition(Object value, QueryFilter.Op operation);

    @Override
    public Condition findLabelCondition(Either<Map<?, ?>, String> value, QueryFilter.Op operation) {
        return findCondition(value.isLeft() ? value.getLeft() : value.getRight(), operation);
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public ArrayListTotal<Flow> find(Pageable pageable, @Nullable String tenantId, @Nullable List<QueryFilter> filters) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                DSLContext context = DSL.using(configuration);

                SelectConditionStep<Record1<Object>> select = getFindFlowSelect(tenantId, filters, context, null);

                return (ArrayListTotal) this.jdbcRepository.fetchPage(context, select, pageable);
            });
    }

    @Override
    public ArrayListTotal<Flow> find(
        Pageable pageable,
        @Nullable String tenantId,
        String namespace,
        @Nullable Class<? extends org.flow.core.models.triggers.AbstractTrigger> triggerClass) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                DSLContext context = DSL.using(configuration);
                return (ArrayListTotal) this.jdbcRepository.fetchPage(
                    context,
                    getFindFlowSelect(tenantId, null, context, null)
                        .and(findTriggerClassCondition(triggerClass))
                        .and(table().NAMESPACE.eq(namespace)),
                    pageable
                );
            });
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public ArrayListTotal<Flow> find(
        Pageable pageable,
        @Nullable String tenantId,
        @Nullable Class<? extends org.flow.core.models.triggers.AbstractTrigger> triggerClass) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                DSLContext context = DSL.using(configuration);
                return (ArrayListTotal) this.jdbcRepository.fetchPage(
                    context,
                    getFindFlowSelect(tenantId, null, context, null)
                        .and(findTriggerClassCondition(triggerClass)),
                    pageable
                );
            });
    }

    @Override
    public ArrayListTotal<FlowWithSource> findWithSource(Pageable pageable, @Nullable String tenantId, @Nullable List<QueryFilter> filters) {
        return this.findWithSource(pageable, tenantId, filters, false);
    }

    @Override
    public ArrayListTotal<FlowWithSource> findWithSourceExcludingDrafts(Pageable pageable, @Nullable String tenantId, @Nullable List<QueryFilter> filters) {
        return this.findWithSource(pageable, tenantId, filters, true);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private ArrayListTotal<FlowWithSource> findWithSource(Pageable pageable, @Nullable String tenantId, @Nullable List<QueryFilter> filters, boolean excludeDraft) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                DSLContext context = DSL.using(configuration);
                SelectConditionStep<Record> select = getFindFlowSelect(tenantId, filters, context, List.of(table().SOURCE_CODE), excludeDraft);

                return (ArrayListTotal) this.jdbcRepository.fetchPage(
                    context,
                    select,
                    pageable,
                    record -> FlowWithSource.of(
                        (Flow) jdbcRepository.map(record),
                        record.get(Tables.FLOWS.SOURCE_CODE.getName(), String.class)
                    )
                );
            });
    }

    @SuppressWarnings("unchecked")
    private <R extends Record> SelectConditionStep<R> getFindFlowSelect(String tenantId, List<QueryFilter> filters, DSLContext context, List<? extends Field<?>> additionalFieldsToSelect) {
        return getFindFlowSelect(tenantId, filters, context, additionalFieldsToSelect, false);
    }

    private <R extends Record> SelectConditionStep<R> getFindFlowSelect(String tenantId, List<QueryFilter> filters, DSLContext context, List<? extends Field<?>> additionalFieldsToSelect, boolean excludeDraft) {
        var select = this.fullTextSelect(tenantId, context, additionalFieldsToSelect != null ? additionalFieldsToSelect : List.of(), excludeDraft);
        select = select.and(this.filter(filters, null, Resource.FLOW));
        return (SelectConditionStep<R>) select;
    }

    protected Field<?> getColumnField(QueryFilter.Field field) {
        if (QueryFilter.Field.FLOW_ID.equals(field)) {
            return table().ID;
        } else {
            return super.getColumnField(field);
        }
    }

    abstract protected Condition findSourceCodeCondition(String query);

    abstract protected Condition findTriggerClassCondition(Class<? extends org.flow.core.models.triggers.AbstractTrigger> triggerClass);

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public ArrayListTotal<SearchResult<Flow>> findSourceCode(Pageable pageable, @Nullable String query, boolean caseSensitive, boolean wholeWord, boolean regex, SourceSearchScope scope, @Nullable String tenantId, @Nullable String namespace) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                DSLContext context = DSL.using(configuration);

                SelectConditionStep<Record> select = this.fullTextSelect(tenantId, context, Collections.singletonList(table().SOURCE_CODE));

                if (query != null && !regex) {
                    select = select.and(this.findSourceCodeCondition(query));
                }

                if (namespace != null) {
                    select = select.and(DSL.or(table().NAMESPACE.eq(namespace), table().NAMESPACE.startsWith(namespace + ".")));
                }

                List<SearchResult<Flow>> results = select
                    .limit(SourceSearchMatcher.MAX_SOURCE_SEARCH_CANDIDATES)
                    .fetch()
                    .stream()
                    .map(record -> new SearchResult<>(
                        (Flow) this.jdbcRepository.map(record),
                        query == null
                            ? List.<SourceMatch>of()
                            : SourceSearchMatcher.findMatches(record.get(table().SOURCE_CODE), query, caseSensitive, wholeWord, regex, scope),
                        true
                    ))
                    .filter(result -> query == null || !result.getMatches().isEmpty())
                    .sorted(java.util.Comparator.comparing((SearchResult<Flow> r) -> r.getModel().getNamespace())
                        .thenComparing(r -> r.getModel().getId()))
                    .toList();

                return pageable == null || pageable.getSize() == -1
                    ? new ArrayListTotal<>(results, results.size())
                    : ArrayListTotal.of(pageable, results);
            });
    }

    @Override
    public FlowWithSource create(GenericFlow flow) throws ConstraintViolationException {
        if (this.findById(flow.getTenantId(), flow.getNamespace(), flow.getId()).isPresent()) {
            throw AlreadyExistsException.of("Flow", flow.getId(), flow.getNamespace());
        }
        return this.save(flow, CrudEventType.CREATE);
    }

    @SneakyThrows({ FlowProcessingException.class })
    @Override
    public FlowWithSource update(GenericFlow flow, FlowInterface previous) throws ConstraintViolationException {
        try {
            // For drafts the YAML may be unparsable; if parsing fails we skip all
            // validation since draft revisions are intentionally allowed to carry invalid content.
            FlowWithSource flowWithDefault = flowParsingService.parseForValidation(flow);
            // Drafts are allowed to be saved invalid - they will fail at execution time instead.
            // Read the draft flag from the original GenericFlow (set from the API draft flag) rather
            // than from flowWithDefault, since `parse` re-parses the YAML source which
            // does not carry the draft field.
            if (!flow.isDraft()) {
                modelValidator.validate(flowWithDefault);
            }

            Flow previousFlow;
            if (previous instanceof Flow o) {
                previousFlow = o;
            } else {
                previousFlow = flowParsingService.parse(previous, false);
            }

            // Check update
            Optional<ConstraintViolationException> checkUpdate = previousFlow.validateUpdate(flowWithDefault);
            if (checkUpdate.isPresent()) {
                throw checkUpdate.get();
            }
        } catch (FlowProcessingException e) {
            if (!flow.isDraft()) {
                throw e;
            }
            // Draft with unparsable YAML: skip validation entirely.
        }

        // Persist
        return this.save(flow, CrudEventType.UPDATE);
    }

    @SneakyThrows(FlowProcessingException.class)
    @VisibleForTesting
    public FlowWithSource save(GenericFlow flow, CrudEventType crudEventType) throws ConstraintViolationException {

        // Ensure the flow is parseable before saving.
        // For drafts with unparsable YAML, fall back to a FlowWithException so the raw source can
        // still be persisted without throwing.
        FlowWithSource flowWithSource;
        try {
            flowWithSource = flowParsingService.parse(flow, false);
        } catch (FlowProcessingException e) {
            if (!flow.isDraft()) {
                throw e;
            }
            flowWithSource = FlowWithException.from(flow, e);
        }

        // Check whether existing Flow is equal.
        FlowWithSource nullOrExisting = this.findByIdWithSource(flow.getTenantId(), flow.getNamespace(), flow.getId()).orElse(null);
        if (nullOrExisting != null && nullOrExisting.isSameWithSource(flow)) {
            return nullOrExisting;
        }

        // Update revision
        List<FlowWithSource> revisions = this.findRevisions(flow.getTenantId(), flow.getNamespace(), flow.getId(), true);
        final int revision = revisions.isEmpty() ? 1 : revisions.getLast().getRevision() + 1;

        flow = flow.toBuilder().revision(revision).updated(Instant.now()).build();

        Map<Field<?>, Object> fields = this.jdbcRepository.persistFields(flow);
        fields.put(table().SOURCE_CODE, flow.getSource());

        this.jdbcRepository.persist(flow, fields);

        eventPublisher.publishEvent(new CrudEvent<>(flow, nullOrExisting, crudEventType));

        // draft is not part of the YAML source so parsing loses it; restore from the original flow.
        return flowWithSource.toBuilder()
            .revision(revision)
            .draft(flow.isDraft())
            .build();
    }

    @SneakyThrows
    @Override
    public FlowWithSource delete(FlowInterface flow) {
        Optional<FlowWithSource> existing = this.findByIdWithSource(flow.getTenantId(), flow.getNamespace(), flow.getId(), Optional.ofNullable(flow.getRevision()));
        FlowWithSource existingFlow = existing
            .orElseThrow(() -> new IllegalStateException("Flow " + flow.getId() + " doesn't exists"));

        Optional<FlowWithSource> last = this.findByIdWithSource(flow.getTenantId(), flow.getNamespace(), flow.getId());
        if (last.isEmpty()) {
            throw new IllegalStateException("Flow " + flow.getId() + " doesn't exists");
        }

        if (!last.get().getRevision().equals(existingFlow.getRevision())) {
            throw new IllegalStateException("Trying to deleted old revision, wanted " + existingFlow.getRevision() + ", last revision is " + last.get().getRevision());
        }

        return deleteFlow(flow, existingFlow);
    }

    @SneakyThrows
    @Override
    public FlowWithSource deleteWithoutAcl(FlowInterface flow) {
        Optional<FlowWithSource> existing = this.findByIdWithSourceWithoutAcl(flow.getTenantId(), flow.getNamespace(), flow.getId(), Optional.ofNullable(flow.getRevision()));
        FlowWithSource existingFlow = existing
            .orElseThrow(() -> new IllegalStateException("Flow " + flow.getId() + " doesn't exists"));

        Optional<FlowWithSource> last = this.findByIdWithSourceWithoutAcl(flow.getTenantId(), flow.getNamespace(), flow.getId(), Optional.empty());
        if (last.isEmpty()) {
            throw new IllegalStateException("Flow " + flow.getId() + " doesn't exists");
        }

        if (!last.get().getRevision().equals(existingFlow.getRevision())) {
            throw new IllegalStateException("Trying to deleted old revision, wanted " + existingFlow.getRevision() + ", last revision is " + last.get().getRevision());
        }

        return deleteFlow(flow, existingFlow);
    }

    @SneakyThrows
    @Override
    public void deleteRevisions(String tenantId, String namespace, String id, List<Integer> revisions) {
        List<FlowWithSource> flows = findRevisions(tenantId, namespace, id, true, revisions);
        Integer last = lastRevision(tenantId, namespace, id);
        FlowWithSource lastFlow = null;
        HashMap<FlowInterface, Map<Field<?>, Object>> revisionsToDelete = new HashMap<>();
        for (FlowWithSource flow : flows) {
            if (Objects.equals(flow.getRevision(), last)) {
                lastFlow = flow;
            } else {
                FlowWithSource toDelete = flow.toBuilder().deleted(true).build();
                Map<Field<?>, Object> fields = this.jdbcRepository.persistFields(toDelete.toFlow());
                fields.put(table().SOURCE_CODE, flow.getSource());
                revisionsToDelete.put(toDelete, fields);
            }
        }

        this.jdbcRepository.persistBatch(revisionsToDelete);

        if (lastFlow != null) {
            deleteFlow(lastFlow, lastFlow);
        }
    }

    private FlowWithSource deleteFlow(FlowInterface flow, FlowWithSource existingFlow)
        throws QueueException {
        FlowWithSource deleted = existingFlow.toDeleted();

        Map<Field<?>, Object> fields = this.jdbcRepository.persistFields(deleted.toFlow());
        fields.put(table().SOURCE_CODE, deleted.getSource());

        this.jdbcRepository.persist(deleted, fields);

        eventPublisher.publishEvent(CrudEvent.delete(flow));
        return deleted;
    }

    @Override
    public List<String> findDistinctNamespace(String tenantId) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(
                configuration -> DSL
                    .using(configuration)
                    .select(table().NAMESPACE)
                    .from(fromLastRevision())
                    .where(this.defaultFilter(tenantId))
                    .groupBy(table().NAMESPACE)
                    .fetch()
                    .map(record -> record.getValue(Tables.FLOWS.NAMESPACE.getName(), String.class))
            );
    }

    @Override
    public List<String> findDistinctNamespaceExecutable(String tenantId) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(
                configuration -> DSL
                    .using(configuration)
                    .select(table().NAMESPACE)
                    .from(fromLastRevision())
                    .where(this.defaultExecutionFilter(tenantId))
                    .groupBy(table().NAMESPACE)
                    .fetch()
                    .map(record -> record.getValue(Tables.FLOWS.NAMESPACE.getName(), String.class))
            );
    }

    @Override
    public Flux<Flow> findAsync(String tenantId, List<QueryFilter> filters) {
        return this.findAsync(tenantId, filters, Resource.FLOW);
    }

    protected Flux<Flow> findAsync(String tenantId, @Nullable List<QueryFilter> filters, QueryFilter.Resource resource) {
        if (filters == null || filters.isEmpty()) {
            return findAsync(defaultFilter(tenantId), null);
        }
        Condition condition = this.filter(filters, null, resource);
        return findAsync(defaultFilter(tenantId), condition);
    }

    protected final Flux<Flow> findAsync(Condition defaultFilter, Condition condition, OrderField<Flow>... orderByFields) {
        return Flux.create(
            emitter -> this.jdbcRepository
                .getDslContextWrapper()
                .transaction(configuration ->
                {
                    DSLContext context = DSL.using(configuration);

                    var select = context
                        .select(table().SOURCE_CODE, table().VALUE, table().NAMESPACE, table().TENANT_ID)
                        .from(this.jdbcRepository.getTable())
                        .where(defaultFilter);

                    if (condition != null) {
                        select = select.and(condition);
                    }

                    var fetchQuery = orderByFields != null
                        ? select.orderBy(orderByFields).fetchSize(FETCH_SIZE)
                        : select.fetchSize(FETCH_SIZE);

                    try (var stream = fetchQuery.stream()) {
                        stream
                            .map(record -> (Flow) jdbcRepository.map(record))
                            .forEach(emitter::next);
                    } finally {
                        emitter.complete();
                    }
                }),
            FluxSink.OverflowStrategy.BUFFER
        );
    }

    @Override
    public Integer lastRevision(String tenantId, String namespace, String id) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(
                configuration -> DSL
                    .using(configuration)
                    .fetchValue(
                        DSL.select(table().REVISION)
                            .from(fromLastRevision())
                            .where(this.defaultFilter(tenantId))
                            .and(table().NAMESPACE.eq(namespace))
                            .and(table().ID.eq(id))
                            .limit(1)
                    )
            );
    }

    @Override
    public Boolean existAnyNoAcl(String tenantId) {
        return jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                DSLContext context = DSL.using(configuration);
                return context.fetchExists(
                    context
                        .selectOne()
                        .from(jdbcRepository.getTable())
                        .where(defaultFilterWithNoACL(tenantId, false))
                );
            });
    }

}
