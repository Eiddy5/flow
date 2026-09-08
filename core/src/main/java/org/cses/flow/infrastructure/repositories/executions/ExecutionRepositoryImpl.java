package org.cses.flow.infrastructure.repositories.executions;

import jakarta.inject.Singleton;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.infrastructure.repositories.executions.entries.ExecutionEntry;
import org.cses.flow.infrastructure.repositories.executions.entries.TaskRunEntry;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.exception.DataAccessException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.flow.gen.flow.Tables.EXECUTIONS;
import static org.flow.gen.flow.Tables.TASK_RUNS;
import static org.jooq.impl.DSL.*;

/** Loads and stores the complete Execution snapshot without locking reads. */
@Singleton
public class ExecutionRepositoryImpl implements ExecutionRepository {

    private Map<Execution, String> versions = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static Field<String> storageVersion = field("{0}.xmin::text", String.class, EXECUTIONS);

    /**
     * Loads one aggregate and its ordered children from one database snapshot.
     * @param dsl database context
     * @param companyId tenant identity
     * @param executionId exact execution identity
     * @return the complete execution when present
     */
    @Override
    public Optional<Execution> findById(DSLContext dsl, String companyId, String executionId) {
        return find(dsl, EXECUTIONS.COMPANY_ID.eq(companyId)
                .and(EXECUTIONS.ID.eq(executionId))).stream().findFirst();
    }

    /**
     * Loads the tenant's executions with their ordered children.
     * @param dsl database context
     * @param companyId tenant identity
     * @return complete aggregates in creation order
     */
    @Override
    public List<Execution> findAll(DSLContext dsl, String companyId) {
        return find(dsl, EXECUTIONS.COMPANY_ID.eq(companyId));
    }

    /**
     * Reads a parent and child collection in one query so callbacks never see half a save.
     * @param dsl database context
     * @param condition complete tenant and optional execution selection
     * @return reconstructed domain aggregates
     */
    private List<Execution> find(DSLContext dsl, Condition condition) {
        dsl = dsl.configuration().derive(new org.jooq.impl.DefaultRecordUnmapperProvider()).dsl();
        var children = multiset(select(TASK_RUNS.fields()).from(TASK_RUNS)
                .where(TASK_RUNS.EXECUTION_ID.eq(EXECUTIONS.ID))
                .orderBy(TASK_RUNS.ORDER.asc()))
                .convertFrom(rows -> rows.into(TaskRunEntry.class));
        return dsl.select(EXECUTIONS.fields()).select(storageVersion.as("storage_version"), children)
                .from(EXECUTIONS).where(condition)
                .orderBy(EXECUTIONS.CREATED_AT.asc(), EXECUTIONS.ID.asc())
                .fetch(row -> {
                    ExecutionEntry entry = row.into(ExecutionEntry.class);
                    Execution execution = entry.to(row.get(children).stream().map(TaskRunEntry::to).toList());
                    versions.put(execution, entry.storageVersion);
                    return execution;
                });
    }

    /**
     * Counts executions within one tenant.
     * @param dsl database context
     * @param companyId tenant identity
     * @return number of stored executions
     */
    @Override
    public long count(DSLContext dsl, String companyId) {
        return dsl.fetchCount(EXECUTIONS, EXECUTIONS.COMPANY_ID.eq(companyId));
    }

    /**
     * Writes the already changed domain and all children in one SQL statement.
     * No state transition or business decision is performed by this storage operation.
     * @param dsl database context without a caller-owned business transaction
     * @param execution complete domain snapshot to store
     * @throws org.jooq.exception.DataChangedException when the loaded snapshot has become stale
     * @throws WorkflowException when storage rejects the aggregate
     */
    @Override
    public void save(DSLContext dsl, Execution execution) {
        ExecutionEntry entry = ExecutionEntry.from(execution);
        var fields = entry.toMap();
        fields.remove("lock_version");
        String expected = versions.get(execution);
        var parent = name("stored_execution").as(dsl.insertInto(EXECUTIONS)
                .set(fields).onConflict(EXECUTIONS.COMPANY_ID, EXECUTIONS.ID)
                .doUpdate().set(fields)
                .where(expected == null ? falseCondition() : storageVersion.eq(expected))
                .returningResult(EXECUTIONS.ID, storageVersion.as("storage_version")));
        List<String> ids = execution.taskRuns().stream().map(TaskRun::id).toList();
        var removed = name("removed_task_runs").as(dsl.deleteFrom(TASK_RUNS)
                .where(TASK_RUNS.EXECUTION_ID.in(select(parent.field(EXECUTIONS.ID)).from(parent)))
                .and(TASK_RUNS.ID.notIn(ids)).returning(TASK_RUNS.ID));
        try {
            var statements = new java.util.ArrayList<org.jooq.CommonTableExpression<?>>();
            statements.add(parent);
            statements.add(removed);
            if (!ids.isEmpty()) {
                var records = new java.util.ArrayList<org.jooq.RowN>();
                for (int index = 0; index < execution.taskRuns().size(); index++) {
                    var taskFields = TaskRunEntry.from(execution.id(), execution.taskRuns().get(index), index).toMap();
                    records.add(row(java.util.Arrays.stream(TASK_RUNS.fields())
                            .map(field -> val(taskFields.get(field.getName()), field))
                            .toArray(Field<?>[]::new)));
                }
                var incoming = values(records.toArray(org.jooq.RowN[]::new)).as("incoming",
                        java.util.Arrays.stream(TASK_RUNS.fields()).map(Field::getName).toArray(String[]::new));
                Map<Field<?>, Object> updates = new LinkedHashMap<>();
                for (Field<?> field : TASK_RUNS.fields()) {
                    updates.put(field, excluded(field));
                }
                statements.add(name("stored_task_runs").as(dsl.insertInto(TASK_RUNS).columns(TASK_RUNS.fields())
                        .select(select(incoming.fields()).from(incoming).where(exists(selectOne().from(parent))))
                        .onConflict(TASK_RUNS.ID).doUpdate().set(updates).returning(TASK_RUNS.ID)));
            }
            var saved = dsl.with(statements).selectFrom(parent).fetchOne();
            if (saved == null) {
                throw new org.jooq.exception.DataChangedException("Execution snapshot changed; reload before saving");
            }
            versions.put(execution, saved.get("storage_version", String.class));
        } catch (org.jooq.exception.DataChangedException conflict) {
            throw conflict;
        } catch (DataAccessException exception) {
            throw new WorkflowException("Execution persistence conflict for "
                    + execution.companyId() + ":" + execution.id(), exception);
        }
    }
}
