package org.cses.flow.infrastructure.repositories.executions;

import jakarta.inject.Singleton;
import org.cses.flow.common.Fields;
import org.cses.flow.core.repositories.worker.ExecutionMessage;
import org.cses.flow.executor.ExecutorEvent;
import org.cses.flow.infrastructure.repositories.worker.entries.ExecutionMessageEntry;
import org.cses.flow.infrastructure.datapilot.FlowDataSourceEngine;
import org.cses.flow.infrastructure.repositories.executions.entries.TaskRetryMessageEntry;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.infrastructure.repositories.BaseRepository;
import org.cses.flow.infrastructure.repositories.enhancers.CasEnhancer;
import org.cses.flow.infrastructure.repositories.executions.entries.ExecutionEntry;
import org.cses.flow.infrastructure.repositories.executions.entries.TaskRunEntry;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record2;
import org.jooq.ResultQuery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.flow.gen.flow.Tables.EXECUTIONS;
import static org.flow.gen.flow.Tables.EXECUTION_MESSAGES;
import static org.flow.gen.flow.Tables.TASK_RUNS;
import static org.flow.gen.flow.Tables.TASK_RETRY_MESSAGES;
import static org.jooq.impl.DSL.*;

/** Loads and stores the complete Execution snapshot without locking reads. */
@Singleton
public class ExecutionRepositoryImpl extends BaseRepository implements ExecutionRepository {
    /** 当前实现注入增强器的构造参数名称。 */
    private static final String CAS_ENHANCER = "casEnhancer";
    private CasEnhancer casEnhancer;

    /**
     * 查询所有未发布计划，未来计划也立即交给 Broker 等待。
     * @param dsl 数据库上下文
     * @param limit 正数批量上限
     * @return 尚未确认发布的消息
     */
    @Override
    public List<PendingRetry> pendingRetries(DSLContext dsl, int limit) {
        if (limit < 1) throw new IllegalArgumentException("Invalid retry outbox limit");
        var messages = TASK_RETRY_MESSAGES;
        return query(dsl, messages, messages.PUBLISHED_AT.isNull())
            .asc(messages.DELIVER_AT, messages.EXECUTION_ID).pageSize(limit)
            .list(TaskRetryMessageEntry.class).items.stream().map(TaskRetryMessageEntry::to).toList();
    }

    /**
     * 仅确认同一条不可变计划；发送后宕机允许再次发送。
     * @param dsl 数据库上下文
     * @param retry 已得到 Broker 确认的计划
     * @param now 当前毫秒时间
     */
    @Override
    public void retryPublished(DSLContext dsl, PendingRetry retry, long now) {
        var messages = TASK_RETRY_MESSAGES;
        execute(dsl, context -> {
            context.update(messages).set(messages.PUBLISHED_AT, now)
                .where(messages.COMPANY_ID.eq(retry.companyId()))
                .and(messages.EXECUTION_ID.eq(retry.executionId()))
                .and(messages.TASK_RUN_ID.eq(retry.taskRunId()))
                .and(messages.ATTEMPT_NUMBER.eq(retry.attemptNumber()))
                .and(messages.DELIVER_AT.eq(retry.deliverAt()))
                .and(messages.PUBLISHED_AT.isNull()).execute();
            return null;
        });
    }

    /**
     * 绑定 Execution 根表并组合 CAS 增强器，不取得连接或保存请求状态。
     * @param dataSourceEngine 非空 Flow Engine，提供普通 CRUD 与原子 SQL 执行入口
     * @param casEnhancer 非空、无状态的 CAS 增强器
     */
    public ExecutionRepositoryImpl(FlowDataSourceEngine dataSourceEngine, CasEnhancer casEnhancer) {
        super(dataSourceEngine, EXECUTIONS);
        this.casEnhancer = java.util.Objects.requireNonNull(casEnhancer, CAS_ENHANCER);
    }

    /**
     * 以对象携带版本原子保存完整聚合，整条 SQL 成功后才回填输入对象版本。
     * @param dsl 调用方拥有的非空 Flow 数据库上下文
     * @param execution 非空完整聚合，新建 lock 为 null；保存成功后修改其版本
     * @throws org.jooq.exception.DataChangedException 快照陈旧、根已删除或新建身份重复时抛出
     * @throws WorkflowException 根、子记录或 outbox 写入失败时抛出，版本保持不变
     */
    @Override
    public void save(DSLContext dsl, Execution execution) {
        save(dsl, execution, List.of());
    }

    /**
     * 同一条 CAS SQL 保存聚合、推进事件及本轮任务命令，成功后回填版本。
     * @param dsl 调用方拥有的数据库上下文
     * @param execution 非空完整聚合，必须保留查询时版本；失败后丢弃重读
     * @param messages 同租户同运行的完整任务消息，只读
     * @throws org.jooq.exception.DataChangedException 加载版本陈旧时抛出，不保存任何消息
     */
    @Override
    public void save(DSLContext dsl, Execution execution, List<ExecutionMessage> messages) {
        execution.lock(execute(dsl, context -> {
            ExecutionEntry entry = ExecutionEntry.from(execution);
            var parent = name("stored_execution").as(save(context, entry.toMap(), execution.lock()));
            var statements = new java.util.ArrayList<org.jooq.CommonTableExpression<?>>();
            save(context, execution, parent, statements);
            for (ExecutionMessage message : messages) addMessage(context, execution, parent, statements, message);
            return casEnhancer.save(context.with(statements).select(parent.field(EXECUTIONS.LOCK)).from(parent));
        }));
    }

    /**
     * 成功处理延迟消息后物理删除对应尝试的计划，重复清理无副作用。
     * @param dsl 调用方数据库上下文
     * @param companyId 原租户身份
     * @param executionId 原运行身份
     * @param taskRunId 原节点身份
     * @param attemptNumber 正数目标尝试编号
     */
    @Override
    public void retryConsumed(DSLContext dsl, String companyId, String executionId, String taskRunId, int attemptNumber) {
        deleteRows(dsl, TASK_RETRY_MESSAGES, TASK_RETRY_MESSAGES.COMPANY_ID.eq(companyId)
            .and(TASK_RETRY_MESSAGES.EXECUTION_ID.eq(executionId))
            .and(TASK_RETRY_MESSAGES.TASK_RUN_ID.eq(taskRunId))
            .and(TASK_RETRY_MESSAGES.ATTEMPT_NUMBER.eq(attemptNumber)));
    }

    /**
     * 在单次数据库快照内恢复指定租户和身份的完整聚合。
     * @param dsl 调用方拥有的非空数据库上下文
     * @param companyId 非空白租户身份
     * @param executionId 非空白运行身份
     * @return 携带版本的独立聚合；不存在时为空
     * @throws IllegalArgumentException 任一身份为空或空白时抛出
     */
    @Override
    public Optional<Execution> findById(DSLContext dsl, String companyId, String executionId) {
        return find(dsl, identity(companyId, executionId)).stream().findFirst();
    }

    /**
     * 按创建顺序恢复该租户的全部聚合及有序子集合。
     * @param dsl 调用方拥有的非空数据库上下文
     * @param companyId 非空白租户身份
     * @return 独立聚合集合，不存在时为空
     * @throws IllegalArgumentException 租户为空或空白时抛出
     */
    @Override
    public List<Execution> findAll(DSLContext dsl, String companyId) {
        return find(dsl, company(companyId));
    }

    /**
     * 在指定租户内恢复同一来源的运行快照，按创建顺序返回。
     * @param dsl 调用方拥有的非空数据库上下文
     * @param companyId 非空白租户身份
     * @param originId 非空的最初运行身份
     * @return 独立恢复的快照集合，不存在时为空
     * @throws IllegalArgumentException 租户为空或空白时抛出
     */
    @Override
    public List<Execution> findByOriginId(DSLContext dsl, String companyId, String originId) {
        return find(dsl, company(companyId).and(EXECUTIONS.ORIGIN_ID.eq(originId)));
    }

    /**
     * 单次查询读取根、版本和子集合，避免观察到不完整的保存结果。
     * @param dsl 调用方数据库上下文
     * @param condition 包含租户的完整筛选条件
     * @return 携带加载版本的独立聚合快照
     */
    private List<Execution> find(DSLContext dsl, Condition condition) {
        return execute(dsl, context -> {
            DSLContext snapshot = context.configuration().derive(new org.jooq.impl.DefaultRecordUnmapperProvider()).dsl();
            var children = multiset(select(TASK_RUNS.fields()).from(TASK_RUNS)
                    .where(TASK_RUNS.EXECUTION_ID.eq(EXECUTIONS.ID))
                    .orderBy(TASK_RUNS.ORDER.asc()))
                    .convertFrom(rows -> rows.into(TaskRunEntry.class));
            return snapshot.select(EXECUTIONS.fields()).select(children)
                    .from(EXECUTIONS).where(condition)
                    .orderBy(EXECUTIONS.CREATED_AT.asc(), EXECUTIONS.ID.asc())
                    .fetch(row -> {
                        ExecutionEntry entry = row.into(ExecutionEntry.class);
                        return entry.to(row.get(children).stream().map(TaskRunEntry::to).toList());
                    });
        });
    }

    /**
     * 统计指定租户的持久化运行数量。
     * @param dsl 调用方拥有的非空数据库上下文
     * @param companyId 非空白租户身份
     * @return 非负运行数量
     * @throws IllegalArgumentException 租户为空或空白时抛出
     */
    @Override
    public long count(DSLContext dsl, String companyId) {
        return query(dsl, company(companyId)).count();
    }

    /**
     * 复用公共完整字段写入，再由组合增强器加入只插入或 CAS 更新协议。
     * @param dsl 调用方数据库上下文
     * @param fields 包含完整身份的 Entry 字段 Map；不修改输入，保留显式 NULL
     * @param expected 加载版本；null 表示仅新建
     * @return 尚未执行的根保存语句，成功返回身份与版本
     * @throws IllegalArgumentException 身份无效或版本为负数时抛出
     * @throws org.jooq.exception.DataChangedException 版本已达上限时抛出
     */
    private ResultQuery<Record2<String, Long>> save(DSLContext dsl, Map<String, Object> fields, Long expected) {
        Condition identity = identity((String) fields.get(EXECUTIONS.COMPANY_ID.getName()),
                (String) fields.get(EXECUTIONS.ID.getName()));
        Map<String, Object> values = casEnhancer.fields(fields, EXECUTIONS.LOCK);
        if (expected == null) {
            return casEnhancer.save(insertStatement(dsl, values), EXECUTIONS.COMPANY_ID, EXECUTIONS.ID, EXECUTIONS.LOCK);
        }
        return casEnhancer.save(updateStatement(dsl, values), EXECUTIONS.ID, EXECUTIONS.LOCK,
                identity, expected);
    }

    /**
     * 校验 Execution 的租户并构造查询条件。
     * @param companyId 非空白租户身份
     * @return 当前租户的 Execution 条件
     */
    private Condition company(String companyId) {
        if (companyId == null || companyId.isBlank()) {
            throw new IllegalArgumentException(Fields.COMPANY_ID + " must not be blank");
        }
        return EXECUTIONS.COMPANY_ID.eq(companyId);
    }

    /**
     * 校验运行身份并构造完整条件。
     * @param companyId 非空白租户身份
     * @param id 非空白运行身份
     * @return 当前租户内指定运行的条件
     */
    private Condition identity(String companyId, String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(Fields.ID + " must not be blank");
        }
        return company(companyId).and(EXECUTIONS.ID.eq(id));
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
            throw casEnhancer.conflict();
        }
        long version = execute(dsl, context -> {
            ExecutionEntry sourceEntry = ExecutionEntry.from(source);
            var parent = name("stored_execution").as(save(context, sourceEntry.toMap(), expected));
            ExecutionEntry derivedEntry = ExecutionEntry.from(derived);
            derivedEntry.lock = 0L;
            var fields = derivedEntry.toMap();
            var created = name("created_execution").as(context.insertInto(EXECUTIONS)
                    .columns(EXECUTIONS.fields())
                    .select(select(java.util.Arrays.stream(EXECUTIONS.fields())
                            .map(field -> val(fields.get(field.getName()), field)).toArray(Field<?>[]::new))
                            .where(exists(selectOne().from(parent))))
                    .returningResult(EXECUTIONS.ID, EXECUTIONS.LOCK));
            var statements = new java.util.ArrayList<org.jooq.CommonTableExpression<?>>();
            save(context, source, parent, statements);
            save(context, derived, created, statements);
            return casEnhancer.save(context.with(statements).select(parent.field(EXECUTIONS.LOCK)).from(parent)
                    .where(exists(selectOne().from(created))));
        });
        source.lock(version);
        derived.lock(0L);
    }

    /**
     * 将根 CAS、子集合、推进事件及重试计划写入同条 SQL，根保存失败不产生消息。
     * @param dsl 调用方数据库上下文
     * @param execution 非空完整聚合，自有节点保存到子表，继承快照留在根 JSONB
     * @param parent 返回根身份的 CAS 语句，只读
     * @param statements 当前可变 CTE 列表，会追加本聚合及其所属消息写入
     */
    private void save(DSLContext dsl, Execution execution,
            org.jooq.CommonTableExpression<?> parent,
            List<org.jooq.CommonTableExpression<?>> statements) {
        statements.add(parent);
        addMessage(dsl, execution, parent, statements, ExecutionMessage.advance(ExecutorEvent.from(
            execution.id(), execution.companyId(), ExecutorEvent.EventType.UPDATED)));
        var retryCleanup = TASK_RETRY_MESSAGES.COMPANY_ID.eq(execution.companyId())
            .and(TASK_RETRY_MESSAGES.EXECUTION_ID.eq(execution.id()));
        if (!execution.isTerminal()) {
            var waiting = execution.ownTaskRuns().stream().filter(run -> run.state().is(State.Type.RETRY_WAIT))
                .map(run -> TASK_RETRY_MESSAGES.TASK_RUN_ID.eq(run.id())
                    .and(TASK_RETRY_MESSAGES.ATTEMPT_NUMBER.eq(run.attemptNumber() + 1)))
                .reduce(org.jooq.impl.DSL.falseCondition(), Condition::or);
            retryCleanup = retryCleanup.and(waiting.not());
        }
        statements.add(name(parent.getName() + "_expired_retries").as(dsl.deleteFrom(TASK_RETRY_MESSAGES)
            .where(retryCleanup).and(exists(selectOne().from(parent))).returning(TASK_RETRY_MESSAGES.TASK_RUN_ID)));
        if (execution.isTerminal()) {
            var event = org.cses.flow.scheduler.events.SchedulerEvent.from("FLOW_TERMINATED",
                execution.companyId(), execution.id(), execution.flowKey(), execution.flowVersion(),
                null, null, execution.id(), execution.creator().id(),
                Map.of("state", execution.state().current().name()));
            var outbox = org.cses.flow.infrastructure.repositories.triggers.entries.SchedulerEventEntry.from(
                event, org.cses.flow.core.utils.TimeUtil.now());
            var events = org.flow.gen.flow.Tables.SCHEDULER_EVENTS;
            statements.add(name(parent.getName() + "_terminal_event").as(dsl.insertInto(events)
                .columns(events.COMPANY_ID, events.ID, events.KIND, events.PAYLOAD,
                    events.CREATED_AT, events.RETRY_AT, events.ATTEMPTS)
                .select(select(val(outbox.companyId), val(outbox.id), val(outbox.kind), val(outbox.payload),
                    val(outbox.createdAt), val(outbox.retryAt), val(0)).from(parent))
                .onConflictDoNothing().returning(events.ID)));
        }

        var retryMessages = TASK_RETRY_MESSAGES;
        for (TaskRun run : execution.ownTaskRuns()) {
            if (!run.state().is(State.Type.RETRY_WAIT)) continue;
            var retry = TaskRetryMessageEntry.from(PendingRetry.from(execution.companyId(), execution.id(),
                run.id(), run.attemptNumber() + 1, run.nextRetryAt()));
            statements.add(name(parent.getName() + "_retry_" + statements.size()).as(dsl.insertInto(retryMessages)
                .columns(retryMessages.COMPANY_ID, retryMessages.EXECUTION_ID, retryMessages.TASK_RUN_ID,
                    retryMessages.ATTEMPT_NUMBER, retryMessages.DELIVER_AT)
                .select(select(val(retry.companyId), val(retry.executionId), val(retry.taskRunId),
                    val(retry.attemptNumber), val(retry.deliverAt)).from(parent))
                .onConflictDoNothing().returning(retryMessages.TASK_RUN_ID)));
        }

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

    /**
     * 将完整消息写入由根 CAS 成功约束的 CTE，不覆盖稳定身份的正文。
     * @param dsl 调用方数据库上下文
     * @param execution 消息所属完整聚合，只读
     * @param parent 根 CAS 返回身份的 CTE
     * @param statements 当前原子 SQL 列表，会追加待执行消息写入
     * @param message 与聚合身份匹配的非空消息，只读
     * @throws IllegalArgumentException 消息租户或运行身份不匹配时抛出
     */
    private void addMessage(DSLContext dsl, Execution execution, org.jooq.CommonTableExpression<?> parent,
            List<org.jooq.CommonTableExpression<?>> statements, ExecutionMessage message) {
        if (!execution.companyId().equals(message.companyId()) || !execution.id().equals(message.executionId())) {
            throw new IllegalArgumentException("Outbox identity does not match Execution");
        }
        var fields = ExecutionMessageEntry.from(message).buildInsertMap();
        statements.add(name(parent.getName() + "_message_" + statements.size()).as(dsl.insertInto(EXECUTION_MESSAGES)
            .columns(EXECUTION_MESSAGES.fields())
            .select(select(java.util.Arrays.stream(EXECUTION_MESSAGES.fields())
                .map(field -> val(fields.get(field.getName()), field)).toArray(Field<?>[]::new)).from(parent))
            .onConflictDoNothing().returning(EXECUTION_MESSAGES.ID)));
    }
}
