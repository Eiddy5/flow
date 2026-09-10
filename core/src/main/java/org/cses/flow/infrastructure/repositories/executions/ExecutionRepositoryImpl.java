package org.cses.flow.infrastructure.repositories.executions;

import jakarta.inject.Singleton;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.infrastructure.repositories.CasRepository;
import org.cses.flow.infrastructure.repositories.executions.entries.ExecutionEntry;
import org.cses.flow.infrastructure.repositories.executions.entries.TaskRunEntry;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.ResultQuery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.flow.gen.flow.Tables.EXECUTIONS;
import static org.flow.gen.flow.Tables.TASK_RUNS;
import static org.jooq.impl.DSL.*;

/** Loads and stores the complete Execution snapshot without locking reads. */
@Singleton
public class ExecutionRepositoryImpl extends CasRepository<Execution> implements ExecutionRepository {

    /** 绑定 Execution 根表及租户、身份、版本字段；不保存任何请求状态。 */
    public ExecutionRepositoryImpl() {
        super(EXECUTIONS, EXECUTIONS.COMPANY_ID, EXECUTIONS.ID, EXECUTIONS.LOCK);
    }

    /**
     * @param execution 完整聚合
     * @return 对象自带版本，新建时为 null
     */
    @Override
    protected Long lock(Execution execution) {
        return execution.lock();
    }

    /**
     * @param execution 已保存聚合
     * @param version SQL 返回的非负版本
     */
    @Override
    protected void lock(Execution execution, long version) {
        execution.lock(version);
    }

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
     * 单次查询读取根、版本和子集合，避免观察到不完整的保存结果。
     * @param dsl 调用方数据库上下文
     * @param condition 包含租户的完整筛选条件
     * @return 携带加载版本的独立聚合快照
     */
    private List<Execution> find(DSLContext dsl, Condition condition) {
        dsl = dsl.configuration().derive(new org.jooq.impl.DefaultRecordUnmapperProvider()).dsl();
        var children = multiset(select(TASK_RUNS.fields()).from(TASK_RUNS)
                .where(TASK_RUNS.EXECUTION_ID.eq(EXECUTIONS.ID))
                .orderBy(TASK_RUNS.ORDER.asc()))
                .convertFrom(rows -> rows.into(TaskRunEntry.class));
        return dsl.select(EXECUTIONS.fields()).select(children)
                .from(EXECUTIONS).where(condition)
                .orderBy(EXECUTIONS.CREATED_AT.asc(), EXECUTIONS.ID.asc())
                .fetch(row -> {
                    ExecutionEntry entry = row.into(ExecutionEntry.class);
                    return entry.to(row.get(children).stream().map(TaskRunEntry::to).toList());
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
     * 将根保存语句与自有 TaskRun 增删改组合为单 SQL，由基类执行并回填版本。
     * @param dsl 调用方数据库上下文
     * @param execution 待保存的完整聚合
     * @param expected 对象查询时版本，null 仅尝试新建
     * @return 成功时返回根版本、CAS 冲突时返回零行的原子 SQL
     */
    @Override
    protected ResultQuery<Record1<Long>> save(DSLContext dsl, Execution execution, Long expected) {
        ExecutionEntry entry = ExecutionEntry.from(execution);
        var root = save(dsl, entry.toMap(), expected);
        var parent = name("stored_execution").as(root);
        var statements = new java.util.ArrayList<org.jooq.CommonTableExpression<?>>();
        save(dsl, execution, parent, statements);
        return dsl.with(statements).select(parent.field(EXECUTIONS.LOCK)).from(parent);
    }
    /**
     * 单 SQL 保存源与派生聚合；源 CAS 成功才允许插入新根，成功后回填两个对象版本。
     * @param dsl 调用方数据库上下文
     * @param source 携带查询时版本且已完成领域变更的源快照
     * @param derived 从源派生的新快照，lock 必须为 null
     * @throws org.jooq.exception.DataChangedException 源版本陈旧或新建/更新身份不符合要求
     * @throws WorkflowException 任意根或子记录写入失败，两个对象版本保持不变
     */
    @Override
    public void save(DSLContext dsl, Execution source, Execution derived) {
        Long expected = source.lock();
        if (expected == null || derived.lock() != null) {
            throw conflict();
        }
        ExecutionEntry sourceEntry = ExecutionEntry.from(source);
        var parent = name("stored_execution").as(save(dsl, sourceEntry.toMap(), expected));
        ExecutionEntry derivedEntry = ExecutionEntry.from(derived);
        derivedEntry.lock = 0L;
        var fields = derivedEntry.toMap();
        var created = name("created_execution").as(dsl.insertInto(EXECUTIONS)
                .columns(EXECUTIONS.fields())
                .select(select(java.util.Arrays.stream(EXECUTIONS.fields())
                        .map(field -> val(fields.get(field.getName()), field)).toArray(Field<?>[]::new))
                        .where(exists(selectOne().from(parent))))
                .returningResult(EXECUTIONS.ID, EXECUTIONS.LOCK));
        var statements = new java.util.ArrayList<org.jooq.CommonTableExpression<?>>();
        save(dsl, source, parent, statements);
        save(dsl, derived, created, statements);
        save(source, dsl.with(statements).select(parent.field(EXECUTIONS.LOCK)).from(parent)
                .where(exists(selectOne().from(created))));
        lock(derived, 0L);
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
