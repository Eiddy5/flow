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
import org.jooq.InsertValuesStepN;

import java.util.List;
import java.util.Optional;

import static org.flow.gen.flow.Tables.EXECUTIONS;
import static org.flow.gen.flow.Tables.TASK_RUNS;

@Singleton
public final class ExecutionPostgresRepository
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
        if (entry == null) {
            return Optional.empty();
        }
        List<TaskRun> taskRuns = dsl.select()
            .from(TASK_RUNS)
            .where(TASK_RUNS.EXECUTION_ID.eq(executionId))
            .orderBy(TASK_RUNS.ORDER.asc())
            .fetchInto(TaskRunEntry.class)
            .stream()
            .map(TaskRunEntry::toDomain)
            .toList();
        return Optional.of(entry.toDomain(taskRuns));
    }

    @Override
    public List<Execution> findAll(
        DSLContext dsl,
        String companyId
    ) {
        return dsl.select(EXECUTIONS.ID)
            .from(EXECUTIONS)
            .where(EXECUTIONS.COMPANY_ID.eq(companyId))
            .orderBy(
                EXECUTIONS.CREATED_AT.asc(),
                EXECUTIONS.ID.asc()
            )
            .fetch(EXECUTIONS.ID)
            .stream()
            .map(executionId -> findById(
                dsl,
                companyId,
                executionId
            ).orElseThrow())
            .toList();
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
        ExecutionEntry stored = dsl.select()
            .from(EXECUTIONS)
            .where(EXECUTIONS.COMPANY_ID.eq(execution.companyId()))
            .and(EXECUTIONS.ID.eq(execution.id()))
            .fetchOneInto(ExecutionEntry.class);

        if (stored == null) {
            ExecutionEntry entry = ExecutionEntry.fromDomain(execution);
            dsl.insertInto(EXECUTIONS)
                .set(entry.buildInsertMap())
                .execute();
        } else {
            ExecutionEntry entry = ExecutionEntry.fromDomain(execution);
            int updated = dsl.update(EXECUTIONS)
                .set(entry.buildUpdateMap())
                .where(EXECUTIONS.COMPANY_ID.eq(execution.companyId()))
                .and(EXECUTIONS.ID.eq(execution.id()))
                .execute();
            if (updated != 1) {
                throw new WorkflowException(
                    "Execution was not updated: " + execution.id()
                );
            }
        }

        replaceTaskRuns(dsl, execution);
    }

    private static void replaceTaskRuns(
        DSLContext dsl,
        Execution execution
    ) {
        dsl.deleteFrom(TASK_RUNS)
            .where(TASK_RUNS.EXECUTION_ID.eq(execution.id()))
            .execute();
        List<TaskRun> taskRuns = execution.taskRuns();
        if (taskRuns.isEmpty()) {
            return;
        }
        InsertValuesStepN<TaskRunsRecord> values = dsl
            .insertInto(TASK_RUNS)
            .columns();
        for (int index = 0; index < taskRuns.size(); index++) {
            TaskRun taskRun = taskRuns.get(index);
            values.values(TaskRunEntry.fromDomain(
                execution.id(),
                taskRun,
                index
            ).toRecord());
        }
        values.execute();
    }

}
