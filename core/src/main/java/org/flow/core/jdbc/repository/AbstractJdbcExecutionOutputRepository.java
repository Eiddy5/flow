package org.flow.core.jdbc.repository;

import org.flow.core.jdbc.AbstractJdbcRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jooq.Field;
import org.jooq.impl.DSL;

import org.flow.gen.flow.Tables;
import org.flow.gen.flow.tables.ExecutionOutputsTable;
import org.flow.gen.flow.records.ExecutionOutputsRecord;
import org.flow.core.models.executions.ExecutionOutput;
import org.flow.core.repositories.ExecutionOutputRepositoryInterface;

public class AbstractJdbcExecutionOutputRepository extends org.flow.core.jdbc.repository.AbstractJdbcRepository implements ExecutionOutputRepositoryInterface {

    private AbstractJdbcRepository<ExecutionOutput> jdbcRepository;

    public AbstractJdbcExecutionOutputRepository(AbstractJdbcRepository<ExecutionOutput> jdbcRepository) {
        this.jdbcRepository = jdbcRepository;
    }

    @Override
    protected ExecutionOutputsTable table() {
        return jdbcRepository.getTable(Tables.EXECUTION_OUTPUTS);
    }

    @Override
    public ExecutionOutput save(ExecutionOutput executionOutput) {
        Map<Field<?>, Object> fields = HashMap.newHashMap(3);
        fields.put(table().TENANT_ID, executionOutput.tenantId());
        fields.put(table().VALUE, executionOutput.value());
        fields.put(table().URI, executionOutput.uri());
        jdbcRepository.persist(executionOutput, fields);
        return executionOutput;
    }

    @Override
    public Optional<ExecutionOutput> findById(String tenantId, String executionId) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                var select = DSL
                    .using(configuration)
                    .selectFrom(table())
                    .where(buildTenantCondition(tenantId))
                    .and(table().KEY.eq(executionId))
                    .limit(1);

                return Optional.ofNullable(select.fetchAny()).map(record -> map(record));
            });
    }

    @Override
    public int purgeByExecutionIds(List<String> executionIds) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                var delete = DSL
                    .using(configuration)
                    .delete(table())
                    .where(table().KEY.in(executionIds));

                return delete.execute();
            });
    }

    private ExecutionOutput map(ExecutionOutputsRecord record) {
        return new ExecutionOutput(record.get(table().KEY), record.get(table().TENANT_ID), record.get(table().VALUE), record.get(table().URI));
    }
}
