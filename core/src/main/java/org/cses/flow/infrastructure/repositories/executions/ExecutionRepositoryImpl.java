package org.cses.flow.infrastructure.repositories.executions;

import jakarta.inject.Singleton;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.infrastructure.repositories.CasSupport;
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
     * Loads the tenant's complete derivation tree without traversing other tenants.
     * @param dsl read context
     * @param companyId owning tenant
     * @param originId first Execution ID
     * @return independently restored snapshots in creation order
     */
    @Override
    public List<Execution> findByOriginId(DSLContext dsl, String companyId, String originId) {
        return find(dsl, EXECUTIONS.COMPANY_ID.eq(companyId).and(EXECUTIONS.ORIGIN_ID.eq(originId)));
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
        DSLContext readContext = dsl;
        return dsl.select(EXECUTIONS.fields()).select(children)
                .from(EXECUTIONS).where(condition)
                .orderBy(EXECUTIONS.CREATED_AT.asc(), EXECUTIONS.ID.asc())
                .fetch(row -> {
                    ExecutionEntry entry = row.into(ExecutionEntry.class);
                    Execution execution = entry.to(row.get(children).stream().map(TaskRunEntry::to).toList());
                    CasSupport.loaded(readContext, EXECUTIONS, execution, entry.lock);
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
     * @param dsl managed database operation context
     * @param execution complete domain snapshot to store
     * @throws org.jooq.exception.DataChangedException when the loaded snapshot has become stale
     * @throws WorkflowException when storage rejects the aggregate
     * @throws IllegalStateException when the database operation is absent or closed
     */
    @Override
    public void save(DSLContext dsl, Execution execution) {
        Long expected = CasSupport.saving(dsl, EXECUTIONS, execution);
        ExecutionEntry entry = ExecutionEntry.from(execution);
        var fields = entry.toMap();
        fields.remove(EXECUTIONS.LOCK.getName());
        org.jooq.ResultQuery<org.jooq.Record2<String, Long>> root;
        if (expected == null) {
            root = dsl.insertInto(EXECUTIONS).set(fields).set(EXECUTIONS.LOCK, 0L)
                    .onConflict(EXECUTIONS.COMPANY_ID, EXECUTIONS.ID).doNothing()
                    .returningResult(EXECUTIONS.ID, EXECUTIONS.LOCK);
        } else {
            fields.remove(EXECUTIONS.COMPANY_ID.getName());
            fields.remove(EXECUTIONS.ID.getName());
            root = CasSupport.update(dsl, EXECUTIONS, fields,
                    EXECUTIONS.COMPANY_ID.eq(entry.companyId).and(EXECUTIONS.ID.eq(entry.id)),
                    EXECUTIONS.LOCK, expected)
                    .returningResult(EXECUTIONS.ID, EXECUTIONS.LOCK);
        }
        var parent = name("stored_execution").as(root);
        try {
            var statements = new java.util.ArrayList<org.jooq.CommonTableExpression<?>>();
            save(dsl, execution, parent, statements);
            var saved = dsl.with(statements).selectFrom(parent).fetchOne();
            if (saved == null) {
                throw CasSupport.conflict();
            }
            CasSupport.saved(dsl, EXECUTIONS, execution, saved.get(EXECUTIONS.LOCK));
        } catch (org.jooq.exception.DataChangedException conflict) {
            throw conflict;
        } catch (DataAccessException exception) {
            throw new WorkflowException("Execution persistence conflict for "
                    + execution.companyId() + ":" + execution.id(), exception);
        }
    }
    /**
     * Saves the changed source and a new derived snapshot in one SQL statement.
     * Source CAS gates insertion; any child or identity failure rolls back both snapshots.
     * @param dsl managed database operation context
     * @param source previously loaded source after its domain transition
     * @param derived new derived Execution, never previously persisted
     * @throws org.jooq.exception.DataChangedException when source CAS or scope ownership fails
     * @throws WorkflowException when either snapshot cannot be stored
     */
    @Override
    public void save(DSLContext dsl, Execution source, Execution derived) {
        Long expected = CasSupport.saving(dsl, EXECUTIONS, source);
        if (expected == null || CasSupport.saving(dsl, EXECUTIONS, derived) != null) {
            throw CasSupport.conflict();
        }
        ExecutionEntry sourceEntry = ExecutionEntry.from(source);
        var sourceFields = sourceEntry.toMap();
        sourceFields.remove(EXECUTIONS.COMPANY_ID.getName());
        sourceFields.remove(EXECUTIONS.ID.getName());
        sourceFields.remove(EXECUTIONS.LOCK.getName());
        var parent = name("stored_execution").as(CasSupport.update(dsl, EXECUTIONS, sourceFields,
                EXECUTIONS.COMPANY_ID.eq(source.companyId()).and(EXECUTIONS.ID.eq(source.id())),
                EXECUTIONS.LOCK, expected)
                .returningResult(EXECUTIONS.ID, EXECUTIONS.LOCK));
        ExecutionEntry derivedEntry = ExecutionEntry.from(derived);
        derivedEntry.lock = 0L;
        var fields = derivedEntry.toMap();
        var created = name("created_execution").as(dsl.insertInto(EXECUTIONS)
                .columns(EXECUTIONS.fields())
                .select(select(java.util.Arrays.stream(EXECUTIONS.fields())
                        .map(field -> val(fields.get(field.getName()), field)).toArray(Field<?>[]::new))
                        .where(exists(selectOne().from(parent))))
                .returningResult(EXECUTIONS.ID, EXECUTIONS.LOCK));
        try {
            var statements = new java.util.ArrayList<org.jooq.CommonTableExpression<?>>();
            save(dsl, source, parent, statements);
            save(dsl, derived, created, statements);
            if (dsl.with(statements).selectFrom(created).fetchOne() == null) {
                throw CasSupport.conflict();
            }
            CasSupport.saved(dsl, EXECUTIONS, source, expected + 1);
            CasSupport.saved(dsl, EXECUTIONS, derived, 0L);
        } catch (org.jooq.exception.DataChangedException conflict) {
            throw conflict;
        } catch (DataAccessException failure) {
            throw new WorkflowException("Execution replay persistence conflict", failure);
        }
    }

    /**
     * Adds the owned TaskRun writes gated by an already prepared root statement.
     * Inherited snapshots are stored by ExecutionEntry in the root JSONB column.
     * @param dsl caller's database context
     * @param execution complete snapshot whose owned runs are saved
     * @param parent root write returning its Execution ID
     * @param statements mutable CTE list receiving this root and its child writes
     */
    private void save(DSLContext dsl, Execution execution,
            org.jooq.CommonTableExpression<?> parent,
            List<org.jooq.CommonTableExpression<?>> statements) {
        statements.add(parent);
        List<TaskRun> runs = execution.ownTaskRuns();
        List<String> ids = runs.stream().map(TaskRun::id).toList();
        statements.add(name(parent.getName() + "_removed_runs").as(dsl.deleteFrom(TASK_RUNS)
                .where(TASK_RUNS.EXECUTION_ID.in(select(parent.field(EXECUTIONS.ID)).from(parent)))
                .and(TASK_RUNS.ID.notIn(ids)).returning(TASK_RUNS.ID)));
        if (runs.isEmpty()) return;
        var records = new java.util.ArrayList<org.jooq.RowN>();
        for (int index = 0; index < runs.size(); index++) {
            var taskFields = TaskRunEntry.from(execution.id(), runs.get(index), index).toMap();
            records.add(row(java.util.Arrays.stream(TASK_RUNS.fields())
                    .map(field -> val(taskFields.get(field.getName()), field)).toArray(Field<?>[]::new)));
        }
        var incoming = values(records.toArray(org.jooq.RowN[]::new)).as("incoming",
                java.util.Arrays.stream(TASK_RUNS.fields()).map(Field::getName).toArray(String[]::new));
        Map<Field<?>, Object> updates = new LinkedHashMap<>();
        for (Field<?> field : TASK_RUNS.fields()) updates.put(field, excluded(field));
        statements.add(name(parent.getName() + "_stored_runs").as(dsl.insertInto(TASK_RUNS)
                .columns(TASK_RUNS.fields())
                .select(select(incoming.fields()).from(incoming).where(exists(selectOne().from(parent))))
                .onConflict(TASK_RUNS.EXECUTION_ID, TASK_RUNS.ID).doUpdate().set(updates)
                .returning(TASK_RUNS.ID)));
    }

}
