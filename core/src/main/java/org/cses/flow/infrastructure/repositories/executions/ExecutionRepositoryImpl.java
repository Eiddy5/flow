package org.cses.flow.infrastructure.repositories.executions;

import jakarta.inject.Singleton;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.infrastructure.repositories.executions.entries.ExecutionEntry;
import org.cses.flow.infrastructure.repositories.executions.entries.TaskRunEntry;
import org.flow.gen.flow.records.TaskRunsRecord;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.InsertValuesStepN;
import org.jooq.JSONB;
import org.jooq.exception.DataAccessException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.flow.gen.flow.Tables.EXECUTIONS;
import static org.flow.gen.flow.Tables.TASK_RUNS;

@Singleton
public final class ExecutionRepositoryImpl
    implements ExecutionRepository {

    @Override
    public Optional<Execution> findById(
        DSLContext dsl,
        String companyId,
        String executionId
    ) {
        ExecutionEntry entry = dsl.select()
            .from(EXECUTIONS)
            .where(EXECUTIONS.COMPANY_ID.eq(companyId))
            .and(EXECUTIONS.ID.eq(executionId))
            .forShare()
            .fetchOneInto(ExecutionEntry.class);
        return restore(dsl, entry);
    }

    @Override
    public List<Execution> findAll(
        DSLContext dsl,
        String companyId
    ) {
        List<ExecutionEntry> entries = dsl.select()
            .from(EXECUTIONS)
            .where(EXECUTIONS.COMPANY_ID.eq(companyId))
            .orderBy(
                EXECUTIONS.CREATED_AT.asc(),
                EXECUTIONS.ID.asc()
            )
            .fetchInto(ExecutionEntry.class);
        Map<String, List<TaskRun>> taskRunsByExecutionId = readTaskRuns(
            dsl,
            entries.stream().map(entry -> entry.id).toList()
        );
        return entries.stream()
            .map(entry -> entry.to(taskRunsByExecutionId.getOrDefault(
                entry.id,
                List.of()
            )))
            .toList();
    }

    private Map<String, List<TaskRun>> readTaskRuns(
        DSLContext dsl,
        List<String> executionIds
    ) {
        if (executionIds.isEmpty()) {
            return Map.of();
        }
        return dsl.select()
            .from(TASK_RUNS)
            .where(TASK_RUNS.EXECUTION_ID.in(executionIds))
            .orderBy(
                TASK_RUNS.EXECUTION_ID.asc(),
                TASK_RUNS.ORDER.asc()
            )
            .fetchInto(TaskRunEntry.class)
            .stream()
            .collect(Collectors.groupingBy(
                entry -> entry.executionId,
                LinkedHashMap::new,
                Collectors.mapping(
                    TaskRunEntry::to,
                    Collectors.toList()
                )
            ));
    }

    @Override
    public long count(DSLContext dsl, String companyId) {
        return dsl.selectCount()
            .from(EXECUTIONS)
            .where(EXECUTIONS.COMPANY_ID.eq(companyId))
            .fetchOne(0, long.class);
    }

    @Override
    public void save(DSLContext dsl, Execution execution) {
        try {
            ExecutionEntry stored = dsl.select()
                .from(EXECUTIONS)
                .where(EXECUTIONS.COMPANY_ID.eq(execution.companyId()))
                .and(EXECUTIONS.ID.eq(execution.id()))
                .fetchOneInto(ExecutionEntry.class);

            if (stored == null) {
                insert(dsl, execution);
            } else {
                update(dsl, execution);
            }

            replace(dsl, execution);
        } catch (DataAccessException exception) {
            throw persistenceConflict(execution, exception);
        }
    }

    private Optional<Execution> restore(
        DSLContext dsl,
        ExecutionEntry entry
    ) {
        return Optional.ofNullable(entry)
            .map(value -> value.to(readTaskRuns(dsl, value.id)));
    }

    private List<TaskRun> readTaskRuns(
        DSLContext dsl,
        String executionId
    ) {
        return dsl.select()
            .from(TASK_RUNS)
            .where(TASK_RUNS.EXECUTION_ID.eq(executionId))
            .orderBy(TASK_RUNS.ORDER.asc())
            .fetchInto(TaskRunEntry.class)
            .stream()
            .map(TaskRunEntry::to)
            .toList();
    }

    private void insert(
        DSLContext dsl,
        Execution execution
    ) {
        insert(dsl, ExecutionEntry.from(execution));
    }

    private void insert(
        DSLContext dsl,
        ExecutionEntry entry
    ) {
        dsl.insertInto(EXECUTIONS)
            .set(entry.buildInsertMap())
            .execute();
    }

    private void update(
        DSLContext dsl,
        Execution execution
    ) {
        int updated = dsl.update(EXECUTIONS)
            .set(ExecutionEntry.from(execution).buildUpdateMap())
            .where(EXECUTIONS.COMPANY_ID.eq(execution.companyId()))
            .and(EXECUTIONS.ID.eq(execution.id()))
            .execute();
        if (updated != 1) {
            throw new WorkflowException(
                "Execution was not updated: " + execution.id()
            );
        }
    }

    private void replace(
        DSLContext dsl,
        Execution execution
    ) {
        dsl.deleteFrom(TASK_RUNS)
            .where(TASK_RUNS.EXECUTION_ID.eq(execution.id()))
            .execute();
        writeTaskRuns(dsl, execution);
    }

    private void writeTaskRuns(
        DSLContext dsl,
        Execution execution
    ) {
        List<TaskRun> taskRuns = execution.taskRuns();
        if (taskRuns.isEmpty()) {
            return;
        }
        Field<?>[] taskRunColumns = {
            TASK_RUNS.ID,
            TASK_RUNS.EXECUTION_ID,
            TASK_RUNS.TASK_ID,
            TASK_RUNS.PARENT_ID,
            TASK_RUNS.ITERATION,
            TASK_RUNS.EXECUTION_GENERATION_VERSION,
            TASK_RUNS.GENERATION,
            TASK_RUNS.STATE,
            TASK_RUNS.INPUTS,
            TASK_RUNS.OUTPUTS,
            TASK_RUNS.ERROR,
            TASK_RUNS.ORDER
        };
        InsertValuesStepN<TaskRunsRecord> values = dsl
            .insertInto(TASK_RUNS)
            .columns(taskRunColumns);
        writeTaskRuns(values, execution.id(), taskRuns);
        values.execute();
    }

    private void writeTaskRuns(
        InsertValuesStepN<TaskRunsRecord> values,
        String executionId,
        List<TaskRun> taskRuns
    ) {
        for (int index = 0; index < taskRuns.size(); index++) {
            TaskRunEntry entry = TaskRunEntry.from(
                executionId,
                taskRuns.get(index),
                index
            );
            values.values(
                entry.id,
                entry.executionId,
                entry.taskId,
                entry.parentId,
                entry.iteration,
                entry.executionGenerationVersion,
                entry.generation,
                entry.state,
                jsonb(entry.inputs),
                jsonb(entry.outputs),
                entry.error,
                entry.order
            );
        }
    }

    private static JSONB jsonb(org.paas.json.JsonObject value) {
        return value == null ? null : JSONB.valueOf(value.toJson());
    }

    private static WorkflowException persistenceConflict(
        Execution execution,
        DataAccessException exception
    ) {
        return new WorkflowException(
            "Execution persistence conflict for "
                + execution.companyId() + ":" + execution.id(),
            exception
        );
    }

}
