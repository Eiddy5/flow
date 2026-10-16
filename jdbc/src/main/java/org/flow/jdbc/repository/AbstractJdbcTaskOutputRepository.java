package org.flow.jdbc.repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jooq.Field;
import org.jooq.impl.DSL;

import org.flow.gen.flow.Tables;
import org.flow.gen.flow.tables.TaskOutputsTable;
import org.flow.gen.flow.records.TaskOutputsRecord;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.TaskOutput;
import org.flow.core.repositories.TaskOutputRepositoryInterface;
import org.flow.jdbc.AbstractJdbcRepository;

public class AbstractJdbcTaskOutputRepository extends org.flow.jdbc.repository.AbstractJdbcRepository implements TaskOutputRepositoryInterface {

    private AbstractJdbcRepository<TaskOutput> jdbcRepository;

    public AbstractJdbcTaskOutputRepository(AbstractJdbcRepository<TaskOutput> jdbcRepository) {
        this.jdbcRepository = jdbcRepository;
    }

    @Override
    protected TaskOutputsTable table() {
        return jdbcRepository.getTable(Tables.TASK_OUTPUTS);
    }

    @Override
    public TaskOutput save(TaskOutput taskOutput) {
        Map<Field<?>, Object> fields = HashMap.newHashMap(5);
        fields.put(table().TASK_RUN_ID, taskOutput.taskRunId());
        fields.put(table().TENANT_ID, taskOutput.tenantId());
        fields.put(table().EXECUTION_ID, taskOutput.executionId());
        fields.put(table().VALUE, taskOutput.value());
        fields.put(table().URI, taskOutput.uri());
        jdbcRepository.persist(taskOutput, fields);
        return taskOutput;
    }

    @Override
    public Optional<TaskOutput> findById(String tenantId, String taskRunId) {
        var condition = table().TASK_RUN_ID.eq(taskRunId);
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                var select = DSL
                    .using(configuration)
                    .selectFrom(table())
                    .where(buildTenantCondition(tenantId))
                    .and(condition)
                    .limit(1);

                return Optional.ofNullable(select.fetchAny()).map(record -> map(record));
            });
    }

    @Override
    public List<TaskOutput> findByExecution(Execution execution) {
        var condition = table().EXECUTION_ID.eq(execution.getId());
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                var select = DSL
                    .using(configuration)
                    .selectFrom(table())
                    .where(buildTenantCondition(execution.getTenantId()))
                    .and(condition);

                return select.fetch().map(record -> map(record));
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
                    .where(table().EXECUTION_ID.in(executionIds));

                return delete.execute();
            });
    }

    private TaskOutput map(TaskOutputsRecord record) {
        return new TaskOutput(record.get(table().TASK_RUN_ID), record.get(table().TENANT_ID), record.get(table().EXECUTION_ID), record.get(table().VALUE), record.get(table().URI));
    }
}
