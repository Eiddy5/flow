package org.flow.core.jdbc.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jooq.*;
import org.jooq.impl.DSL;

import org.flow.gen.flow.Tables;
import org.flow.gen.flow.tables.KvMetadataTable;
import org.flow.core.models.FetchVersion;
import org.flow.core.models.QueryFilter;
import org.flow.core.models.TenantAndNamespace;
import org.flow.core.models.kv.PersistedKvMetadata;
import org.flow.core.repositories.ArrayListTotal;
import org.flow.core.repositories.KvMetadataRepositoryInterface;

import io.micronaut.data.model.Pageable;
import jakarta.annotation.Nullable;

public abstract class AbstractJdbcKvMetadataRepository extends AbstractJdbcCrudRepository<PersistedKvMetadata> implements KvMetadataRepositoryInterface {

    public AbstractJdbcKvMetadataRepository(
        org.flow.core.jdbc.AbstractJdbcRepository<PersistedKvMetadata> jdbcRepository) {
        super(jdbcRepository);
    }

    @Override
    protected KvMetadataTable table() {
        return jdbcRepository.getTable(Tables.KV_METADATA);
    }

    @Override
    protected Condition defaultFilter(String tenantId, boolean allowDeleted) {
        return super.defaultFilter(tenantId, allowDeleted).and(aclCondition(QueryFilter.Resource.KV_METADATA));
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
    public Optional<PersistedKvMetadata> findByName(String tenantId, String namespace, String name) {
        var condition = table().NAMESPACE.eq(namespace)
            .and(table().NAME.eq(name))
            .and(lastCondition());
        return findOne(tenantId, condition, true);
    }

    private Condition findSelect(
        @Nullable List<QueryFilter> filters,
        boolean allowExpired,
        FetchVersion fetchBehavior) {
        var condition = allowExpired ? DSL.noCondition()
            : DSL.or(
                table().EXPIRATION_DATE.greaterThan(OffsetDateTime.now(ZoneOffset.UTC)),
                table().EXPIRATION_DATE.isNull()
            );

        condition = condition.and(this.filter(filters, table().UPDATED, QueryFilter.Resource.KV_METADATA));

        switch (fetchBehavior) {
            case LATEST -> condition = condition.and(lastCondition());
            case OLD -> condition = condition.and(lastCondition(false));
        }

        return condition;
    }

    @Override
    public ArrayListTotal<PersistedKvMetadata> find(Pageable pageable, String tenantId, List<QueryFilter> filters, boolean allowDeleted, boolean allowExpired, FetchVersion fetchBehavior) {
        var condition = findSelect(filters, allowExpired, fetchBehavior);
        return this.findPage(pageable, tenantId, condition, allowDeleted);
    }

    @Override
    public PersistedKvMetadata delete(PersistedKvMetadata persistedKvMetadata) {
        return this.save(persistedKvMetadata.toDeleted());
    }

    @Override
    public Integer purge(List<PersistedKvMetadata> persistedKvsMetadata) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                DSLContext context = DSL.using(configuration);

                Map<TenantAndNamespace, List<PersistedKvMetadata>> byTenantNamespace = persistedKvsMetadata.stream().collect(
                    Collectors.toMap(
                        kvMetadata -> new TenantAndNamespace(kvMetadata.getTenantId(), kvMetadata.getNamespace()),
                        List::of,
                        (kv1, kv2) -> Stream.concat(kv1.stream(), kv2.stream()).toList()
                    )
                );

                return byTenantNamespace.entrySet().stream().reduce(0, (totalForTenantNamespace, e) ->
                {
                    var deleteCondition = context.delete(table())
                        .where(this.defaultFilter(e.getKey().tenantId(), true))
                        .and(table().NAMESPACE.eq(e.getKey().namespace()))
                        .and(table().LAST.in(true, false));
                    if (e.getValue().getFirst().getRevision() == null) {
                        deleteCondition = deleteCondition.and(table().NAME.in(persistedKvsMetadata.stream().map(PersistedKvMetadata::getName).toList()));
                    } else {
                        deleteCondition = deleteCondition.and(
                            DSL.or(
                                e.getValue().stream().map(
                                    kvMetadata -> DSL.and(
                                        table().NAME.eq(kvMetadata.getName()),
                                        table().VERSION.eq(
                                            kvMetadata.getRevision()
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

    @Override
    public PersistedKvMetadata save(PersistedKvMetadata kvMetadata) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                DSLContext context = DSL.using(configuration);

                return saveKvMetadata(context, kvMetadata);
            });
    }

    private PersistedKvMetadata saveKvMetadata(DSLContext context, PersistedKvMetadata kvMetadata) {
        Optional<PersistedKvMetadata> maybePrevious = this.findByName(kvMetadata.getTenantId(), kvMetadata.getNamespace(), kvMetadata.getName());
        PersistedKvMetadata kvMetadataToPersist = kvMetadata.asLast().toBuilder().revision(maybePrevious.map(PersistedKvMetadata::getRevision).orElse(0) + 1).build();
        if (maybePrevious.isPresent()) {
            PersistedKvMetadata previous = maybePrevious.get();
            if (kvMetadata.isDeleted()) {
                // If we are deleting, we just mark the previous as deleted without changing revision and we return directly
                kvMetadataToPersist = previous.toBuilder().deleted(true).updated(Instant.now()).build();
            } else {
                // We mark the previous as not last
                PersistedKvMetadata previousAsNotLast = previous.toBuilder().last(false).build();
                Map<Field<?>, Object> fields = this.jdbcRepository.persistFields(previousAsNotLast);
                this.jdbcRepository.persist(previousAsNotLast, context, fields);
            }
        }

        Map<Field<?>, Object> fields = this.jdbcRepository.persistFields(kvMetadataToPersist);
        this.jdbcRepository.persist(kvMetadataToPersist, context, fields);
        return kvMetadataToPersist;
    }
}
