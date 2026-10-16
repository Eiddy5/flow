package org.flow.jdbc.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jooq.Field;
import org.jooq.impl.DSL;

import org.flow.gen.flow.Tables;
import org.flow.gen.flow.tables.ConcurrencyLimitTable;
import org.flow.core.repositories.ConcurrencyLimitRepositoryInterface;
import org.flow.core.runners.ConcurrencyLimit;

public class AbstractJdbcConcurrencyLimitRepository extends AbstractJdbcRepository implements ConcurrencyLimitRepositoryInterface {
    protected org.flow.jdbc.AbstractJdbcRepository<ConcurrencyLimit> jdbcRepository;

    public AbstractJdbcConcurrencyLimitRepository(org.flow.jdbc.AbstractJdbcRepository<ConcurrencyLimit> jdbcRepository) {
        this.jdbcRepository = jdbcRepository;
    }

    @Override
    protected ConcurrencyLimitTable table() {
        return jdbcRepository.getTable(Tables.CONCURRENCY_LIMIT);
    }

    @Override
    public List<ConcurrencyLimit> find(String tenantId) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                var select = DSL
                    .using(configuration)
                    .select(table().VALUE)
                    .from(table())
                    .where(this.buildTenantCondition(tenantId));

                return this.jdbcRepository.fetch(select);
            });
    }

    @Override
    public ConcurrencyLimit update(ConcurrencyLimit concurrencyLimit) {
        Map<Field<?>, Object> fields = this.jdbcRepository.persistFields(concurrencyLimit);
        this.jdbcRepository.persist(concurrencyLimit, fields);

        return concurrencyLimit;
    }

    @Override
    public ConcurrencyLimit delete(ConcurrencyLimit concurrencyLimit) {
        this.jdbcRepository.delete(concurrencyLimit);
        return concurrencyLimit;
    }

    @Override
    public Optional<ConcurrencyLimit> findById(String tenantId, String namespace, String flowId) {
        return jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                var select = DSL
                    .using(configuration)
                    .select(table().VALUE)
                    .from(table())
                    .where(this.buildTenantCondition(tenantId))
                    .and(table().NAMESPACE.eq(namespace))
                    .and(table().FLOW_ID.eq(flowId));
                return this.jdbcRepository.fetchOne(select);
            });
    }
}
