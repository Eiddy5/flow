package org.flow.jdbc.repository;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jooq.*;
import org.jooq.impl.DSL;

import org.flow.gen.flow.Tables;
import org.flow.gen.flow.tables.NamespaceFileMetadataTable;
import org.flow.core.models.FetchVersion;
import org.flow.core.models.QueryFilter;
import org.flow.core.models.TenantAndNamespace;
import org.flow.core.models.namespaces.files.NamespaceFileMetadata;
import org.flow.core.repositories.ArrayListTotal;
import org.flow.core.repositories.NamespaceFileMetadataRepositoryInterface;

import io.micronaut.data.model.Pageable;
import jakarta.annotation.Nullable;

public abstract class AbstractJdbcNamespaceFileMetadataRepository extends AbstractJdbcRepository implements NamespaceFileMetadataRepositoryInterface {
    protected org.flow.jdbc.AbstractJdbcRepository<NamespaceFileMetadata> jdbcRepository;

    public AbstractJdbcNamespaceFileMetadataRepository(
        org.flow.jdbc.AbstractJdbcRepository<NamespaceFileMetadata> jdbcRepository) {
        this.jdbcRepository = jdbcRepository;
    }

    @Override
    protected NamespaceFileMetadataTable table() {
        return jdbcRepository.getTable(Tables.NAMESPACE_FILE_METADATA);
    }

    @Override
    protected Condition defaultFilter(String tenantId, boolean allowDeleted) {
        return super.defaultFilter(tenantId, allowDeleted).and(aclCondition(QueryFilter.Resource.NAMESPACE_FILE_METADATA));
    }

    private Condition lastCondition(boolean isLast) {
        return table().LAST.eq(isLast);
    }

    private Condition lastCondition() {
        return lastCondition(true);
    }

    abstract protected Condition findCondition(String query);

    @Override
    protected Condition findQueryCondition(String query) {
        return findCondition(query);
    }

    @Override
    public Set<String> findDistinctNamespace(String tenantId) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(
                configuration -> new HashSet<>(
                    DSL
                        .using(configuration)
                        .select(table().NAMESPACE)
                        .from(table())
                        .where(this.defaultFilter(tenantId, false))
                        .and(lastCondition())
                        .groupBy(table().NAMESPACE)
                        .fetch()
                        .map(record -> record.get(table().NAMESPACE))
                )
            );
    }

    @Override
    public Optional<NamespaceFileMetadata> findByPath(String tenantId, String namespace, String path) {
        return jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                Select<Record1<JSONB>> from = DSL
                    .using(configuration)
                    .select(table().VALUE)
                    .from(table())
                    .where(this.defaultFilter(tenantId, true))
                    .and(table().NAMESPACE.eq(namespace))
                    .and(pathCondition(path))
                    .and(lastCondition());
                return this.jdbcRepository.fetchOne(from);
            });
    }

    private SelectConditionStep<Record1<JSONB>> findSelect(
        DSLContext context,
        @Nullable String tenantId,
        @Nullable List<QueryFilter> filters,
        boolean allowDeleted,
        FetchVersion fetchBehavior) {
        SelectConditionStep<Record1<JSONB>> condition = context
            .select(table().VALUE)
            .from(table())
            .where(this.defaultFilter(tenantId, allowDeleted))
            .and(this.filter(filters, table().UPDATED, QueryFilter.Resource.NAMESPACE_FILE_METADATA));

        switch (fetchBehavior) {
            case LATEST -> condition = condition.and(lastCondition());
            case OLD -> condition = condition.and(lastCondition(false));
            case ALL -> condition = condition.and(table().LAST.in(true, false));
        }

        return condition;
    }

    @Override
    public ArrayListTotal<NamespaceFileMetadata> find(Pageable pageable, String tenantId, List<QueryFilter> filters, boolean allowDeleted, FetchVersion fetchBehavior) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                DSLContext context = DSL.using(configuration);

                SelectConditionStep<Record1<JSONB>> select = this.findSelect(
                    context,
                    tenantId,
                    filters,
                    allowDeleted,
                    fetchBehavior
                );

                return this.jdbcRepository.fetchPage(context, select, pageable);
            });
    }

    @Override
    public Integer purge(List<NamespaceFileMetadata> namespaceFilesMetadata) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                DSLContext context = DSL.using(configuration);

                Map<TenantAndNamespace, List<NamespaceFileMetadata>> byTenantNamespace = namespaceFilesMetadata.stream().collect(
                    Collectors.toMap(
                        namespaceFileMetadata -> new TenantAndNamespace(namespaceFileMetadata.getTenantId(), namespaceFileMetadata.getNamespace()),
                        List::of,
                        (nsFile1, nsFile2) -> Stream.concat(nsFile1.stream(), nsFile2.stream()).toList()
                    )
                );

                return byTenantNamespace.entrySet().stream().reduce(0, (totalForTenantNamespace, e) ->
                {
                    var deleteCondition = context.delete(table())
                        .where(this.defaultFilter(e.getKey().tenantId(), true))
                        .and(table().NAMESPACE.eq(e.getKey().namespace()))
                        .and(table().LAST.in(true, false));
                    if (e.getValue().getFirst().getRevision() == null) {
                        deleteCondition = deleteCondition.and(
                            table().PATH.in(
                                namespaceFilesMetadata.stream()
                                    .flatMap(namespaceFileMetadata -> Stream.of(namespaceFileMetadata.path(false), namespaceFileMetadata.path(true)))
                                    .toList()
                            )
                        );
                    } else {
                        deleteCondition = deleteCondition.and(
                            DSL.or(
                                e.getValue().stream().map(
                                    namespaceFileMetadata -> DSL.and(
                                        pathCondition(namespaceFileMetadata.getPath()),
                                        table().VERSION.eq(
                                            namespaceFileMetadata.getRevision()
                                        )
                                    )
                                ).toList()
                            )
                        );
                    }

                    int deletedAmount = deleteCondition.execute();

                    return totalForTenantNamespace + deletedAmount;
                }, Integer::sum);
            });
    }

    private Condition pathCondition(String path) {
        return table().PATH.in(List.of(NamespaceFileMetadata.path(path, false), NamespaceFileMetadata.path(path, true)));
    }

    @Override
    public NamespaceFileMetadata save(NamespaceFileMetadata namespaceFileMetadata) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                DSLContext context = DSL.using(configuration);

                Optional<NamespaceFileMetadata> maybePrevious = this.findByPath(namespaceFileMetadata.getTenantId(), namespaceFileMetadata.getNamespace(), namespaceFileMetadata.getPath());
                NamespaceFileMetadata nsFileMetadataToPersist = namespaceFileMetadata.asLast().toBuilder().deleted(false).revision(maybePrevious.map(previous ->
                {
                    if (previous.isDirectory()) {
                        // Directories stay at revision 1
                        return 1;
                    }
                    return previous.getRevision() + 1;
                }).orElse(1)).created(maybePrevious.map(NamespaceFileMetadata::getCreated).orElse(Instant.now())).build();

                if (maybePrevious.isPresent()) {
                    NamespaceFileMetadata previous = maybePrevious.get();
                    if (namespaceFileMetadata.isDeleted()) {
                        // If we are deleting, we just mark the previous as deleted without changing version and we return directly
                        nsFileMetadataToPersist = previous.toDeleted();
                    } else {
                        // We mark the previous as not last
                        NamespaceFileMetadata previousAsNotLast = previous.toBuilder().last(false).build();
                        Map<Field<?>, Object> fields = this.jdbcRepository.persistFields(previousAsNotLast);
                        this.jdbcRepository.persist(previousAsNotLast, context, fields);
                    }
                }

                Map<Field<?>, Object> fields = this.jdbcRepository.persistFields(nsFileMetadataToPersist);
                this.jdbcRepository.persist(nsFileMetadataToPersist, context, fields);

                return nsFileMetadataToPersist;
            });
    }
}
