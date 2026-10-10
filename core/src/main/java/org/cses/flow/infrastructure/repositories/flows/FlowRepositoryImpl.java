package org.cses.flow.infrastructure.repositories.flows;

import jakarta.inject.Singleton;
import org.cses.flow.common.Fields;
import org.cses.flow.infrastructure.datapilot.FlowDataSourceEngine;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.scheduler.events.SchedulerEvent;
import org.cses.flow.infrastructure.repositories.triggers.entries.SchedulerEventEntry;
import org.cses.flow.core.domains.flows.FlowId;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.infrastructure.repositories.BaseRepository;
import org.cses.flow.extensions.flow.Branch;
import org.cses.flow.infrastructure.repositories.flows.entries.FlowEntry;
import org.cses.flow.infrastructure.repositories.flows.entries.FlowTaskEntry;
import org.flow.gen.flow.records.FlowTasksRecord;
import org.flow.gen.flow.tables.FlowsTable;
import org.jooq.DSLContext;
import org.jooq.Condition;
import org.jooq.InsertValuesStepN;
import org.jooq.exception.DataAccessException;
import org.paas.common.util.StringUtil;
import org.paas.session.RecordState;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.flow.gen.flow.Tables.FLOWS;
import static org.flow.gen.flow.Tables.SCHEDULER_EVENTS;
import static org.flow.gen.flow.Tables.FLOW_TASKS;

@Singleton
public class FlowRepositoryImpl extends BaseRepository implements FlowRepository {

    /**
     * 绑定 Flow 根表与租户身份字段，不创建连接或保存请求状态。
     * @param dataSourceEngine 非空 Flow Engine，由公共基础执行真实 CRUD
     */
    public FlowRepositoryImpl(FlowDataSourceEngine dataSourceEngine) {
        super(dataSourceEngine, FLOWS);
    }

    /**
     * 按完整租户与行身份恢复 Flow 快照及正式任务定义。
     * @param dsl 调用方拥有的非空数据库上下文
     * @param companyId 非空白租户身份
     * @param id 非空白行身份
     * @return 独立 Flow 快照；不存在时为空
     * @throws IllegalArgumentException 任一身份为空或空白时抛出
     */
    @Override
    public Optional<Flow> findById(
            DSLContext dsl,
            String companyId,
            String id
    ) {
        FlowEntry entry = readOne(query(dsl, identity(companyId, id)), FlowEntry.class);
        return restore(dsl, entry);
    }

    /**
     * 按租户、流程键和指定正式版本恢复完整快照。
     * @param dsl 调用方拥有的非空数据库上下文
     * @param flowId 非空且含版本的流程选择器
     * @return 独立的正式快照；不存在时为空
     * @throws IllegalArgumentException 缺少版本或租户为空白时抛出
     */
    @Override
    public Optional<Flow> findByFlowId(
            DSLContext dsl,
            FlowId flowId
    ) {
        long flowVersion = requireVersion(flowId);
        FlowEntry entry = readOne(query(dsl, company(flowId.companyId())
                .and(FLOWS.KEY.eq(flowId.key())).and(FLOWS.DRAFT.eq(false))
                .and(FLOWS.VERSION.eq(flowVersion))), FlowEntry.class);
        return restore(dsl, entry);
    }

    /**
     * 按租户和逻辑流程键恢复最新正式版本。
     * @param dsl 调用方拥有的非空数据库上下文
     * @param flowId 非空且不含版本的流程选择器
     * @return 独立的最新正式快照；不存在时为空
     * @throws IllegalArgumentException 含版本或租户为空白时抛出
     */
    @Override
    public Optional<Flow> findLatestByFlowId(
            DSLContext dsl,
            FlowId flowId
    ) {
        requireLogicalFlow(flowId);
        FlowEntry entry = readOne(query(dsl, company(flowId.companyId())
                .and(FLOWS.KEY.eq(flowId.key())).and(FLOWS.DRAFT.eq(false)))
                .desc(FLOWS.VERSION).pageSize(1), FlowEntry.class);
        return restore(dsl, entry);
    }

    /**
     * 列出同租户各流程键的最新有效草稿，不恢复已经删除的最新行。
     * @param dsl 调用方拥有的非空只读数据库上下文
     * @param companyId 非空白租户身份
     * @return 按更新时间排列的独立草稿只读列表；没有时为空
     * @throws IllegalArgumentException 租户为空白时抛出
     */
    @Override
    public List<Flow> findDrafts(
            DSLContext dsl,
            String companyId
    ) {
        FlowsTable newer = FLOWS.as("newer_draft");
        return query(dsl, company(companyId).and(FLOWS.DRAFT.eq(true))
                .and(org.jooq.impl.DSL.notExists(dsl.selectOne()
                        .from(newer)
                        .where(newer.COMPANY_ID.eq(FLOWS.COMPANY_ID))
                        .and(newer.KEY.eq(FLOWS.KEY))
                        .and(newer.DRAFT.eq(true))
                        .and(newer.VERSION.gt(FLOWS.VERSION))))
                .and(FLOWS.STATUS.ne(RecordState.Delete.getName())))
                .desc(FLOWS.UPDATED_AT).asc(FLOWS.ID).list(FlowEntry.class).items.stream()
                .map(FlowEntry::to)
                .toList();
    }

    /**
     * 读取同租户最新草稿，最新行已删除时返回空。
     * @param dsl 调用方拥有的非空只读数据库上下文
     * @param flowId 非空且不含版本的租户和流程键选择器
     * @return 独立的最新有效草稿；不存在或已删除时为空
     * @throws IllegalArgumentException 选择器含版本或租户为空白时抛出
     */
    @Override
    public Optional<Flow> findDraftByFlowId(
            DSLContext dsl,
            FlowId flowId
    ) {
        requireLogicalFlow(flowId);
        FlowEntry entry = readOne(query(dsl, company(flowId.companyId())
                .and(FLOWS.KEY.eq(flowId.key())).and(FLOWS.DRAFT.eq(true)))
                .desc(FLOWS.VERSION).pageSize(1), FlowEntry.class);
        return Optional.ofNullable(entry)
                .map(FlowEntry::to)
                .filter(flow -> !flow.deleted());
    }

    /**
     * 追加 Flow 行并分配下一版本；正式版本将任务与配置事件一并原子写入。
     * @param dsl 调用方数据库上下文，同一条 SQL 包含全部快照及事件
     * @param flow 非空完整定义，只读其身份和审计事实
     * @return 具有新行 ID 和版本的快照，容器不共享可变状态
     * @throws WorkflowException 数据库拒绝写入时抛出
     * @throws ArithmeticException 版本超过 Long 最大值时抛出
     */
    @Override
    public Flow save(DSLContext dsl, Flow flow) {
        try {
            FlowEntry latestEntry = readOne(query(dsl, company(flow.companyId()).and(FLOWS.KEY.eq(flow.key())))
                .desc(FLOWS.VERSION).pageSize(1), FlowEntry.class);
            Long latest = latestEntry == null ? null : latestEntry.version;
            long version = latest == null
                    ? 1
                    : Math.addExact(latest, 1);
            FlowEntry entry = FlowEntry.from(
                    flow,
                    StringUtil.newId(),
                    version
            );
            execute(dsl, context -> {
                var parent = org.jooq.impl.DSL.name("stored_flow").as(
                        insertStatement(context, entry.toMap()).returning(FLOWS.ID));
                var statement = context.with(parent);
                if (flow.deployed()) {
                    var event = SchedulerEvent.from("FLOW_CHANGED",
                        entry.companyId, entry.id, entry.key, entry.version, null, null, null,
                        flow.updater().id(), Map.of());
                    var outbox = SchedulerEventEntry.from(
                        event, flow.updatedAt());
                    var savedEvent = org.jooq.impl.DSL.name("stored_scheduler_event").as(
                        context.insertInto(SCHEDULER_EVENTS)
                            .set(outbox.toMap()).returning(SCHEDULER_EVENTS.ID));
                    statement = statement.with(savedEvent);
                }
                if (flow.deployed() && !flow.tasks().isEmpty()) {
                    writeTasks(statement, flow, version);
                } else {
                    statement.selectFrom(parent).fetch();
                }
                return null;
            });
            return entry.to(flow.tasks());
        } catch (DataAccessException exception) {
            throw persistenceConflict(flow, exception);
        }
    }

    /**
     * 校验 Flow 的租户并构造查询条件。
     * @param companyId 非空白租户身份
     * @return 当前租户的 Flow 条件
     */
    private Condition company(String companyId) {
        if (companyId == null || companyId.isBlank()) {
            throw new IllegalArgumentException(Fields.COMPANY_ID + " must not be blank");
        }
        return FLOWS.COMPANY_ID.eq(companyId);
    }

    /**
     * 校验 Flow 行身份并构造完整查询条件。
     * @param companyId 非空白租户身份
     * @param id 非空白 Flow 行身份
     * @return 当前租户内指定 Flow 的条件
     */
    private Condition identity(String companyId, String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(Fields.ID + " must not be blank");
        }
        return company(companyId).and(FLOWS.ID.eq(id));
    }

    private long requireVersion(FlowId flowId) {
        if (flowId.version() == null) {
            throw new IllegalArgumentException(
                    "Exact Flow lookup requires a version"
            );
        }
        return flowId.version();
    }

    private void requireLogicalFlow(FlowId flowId) {
        if (flowId.version() != null) {
            throw new IllegalArgumentException(
                    "Logical Flow lookup must not include a version"
            );
        }
    }

    private Optional<Flow> restore(
            DSLContext dsl,
            FlowEntry entry
    ) {
        if (entry == null) {
            return Optional.empty();
        }
        if (Boolean.TRUE.equals(entry.draft)) {
            return Optional.of(entry.to());
        }
        return Optional.of(entry.to(readTasks(
                dsl,
                entry.companyId,
                entry.key,
                entry.version
        )));
    }

    private List<Task> readTasks(
            DSLContext dsl,
            String companyId,
            String flowKey,
            long flowVersion
    ) {
        List<FlowTaskEntry> entries = query(dsl, FLOW_TASKS,
                FLOW_TASKS.COMPANY_ID.eq(companyId).and(FLOW_TASKS.FLOW_KEY.eq(flowKey))
                    .and(FLOW_TASKS.FLOW_VERSION.eq(flowVersion)))
                .asc(FLOW_TASKS.POSITION).list(FlowTaskEntry.class).items;
        Set<String> restored = new HashSet<>();
        List<Task> tasks = readTasks(entries, null, restored);
        if (restored.size() != entries.size()) {
            throw new IllegalStateException(
                    "Persisted Flow Task tree has orphan or cyclic rows for "
                            + flowKey + ":" + flowVersion
            );
        }
        return tasks;
    }

    private List<Task> readTasks(
            List<FlowTaskEntry> entries,
            String parentId,
            Set<String> restored
    ) {
        return entries.stream()
                .filter(entry -> Objects.equals(entry.parentId, parentId))
                .sorted(Comparator.comparingInt(entry -> entry.position))
                .map(entry -> {
                    if (!restored.add(entry.id)) {
                        throw new IllegalStateException(
                                "Duplicate persisted Flow Task id " + entry.id
                        );
                    }
                    return entry.to(
                        readTasks(entries, entry.id, restored)
                    );
                })
                .toList();
    }

    /**
     * 向已有 Flow/事件 CTE 追加完整任务树并执行整条 SQL。
     * @param dsl 包含 Flow 及可选事件插入的语句
     * @param flow 非空正式 Flow，只读其任务树
     * @param flowVersion Repository 分配的正版本号
     */
    private void writeTasks(
            org.jooq.WithStep dsl,
            Flow flow,
            long flowVersion
    ) {
        if (flow.tasks().isEmpty()) {
            return;
        }
        InsertValuesStepN<FlowTasksRecord> values = dsl
                .insertInto(FLOW_TASKS)
                .columns();
        writeTasks(
                values,
                flow.companyId(),
                flow.key(),
                flowVersion,
                null,
                flow.tasks()
        );
        values.execute();
    }

    private void writeTasks(
            InsertValuesStepN<FlowTasksRecord> values,
            String companyId,
            String flowKey,
            long flowVersion,
            String parentId,
            List<Task> tasks
    ) {
        for (int index = 0; index < tasks.size(); index++) {
            Task task = tasks.get(index);
            values.values(FlowTaskEntry.from(
                    companyId,
                    flowKey,
                    flowVersion,
                    task,
                    parentId,
                    index
            ).toRecord());
            if (task instanceof Branch branch) {
                writeTasks(
                        values,
                        companyId,
                        flowKey,
                        flowVersion,
                        task.id(),
                        branch.tasks()
                );
            }
        }
    }

    /**
     * Wraps a database write failure with the affected logical Flow key.
     *
     * @param flow non-null Flow whose company and key identify the failed save
     * @param exception non-null database failure retained as the cause
     * @return a new workflow-layer persistence exception
     */
    private static WorkflowException persistenceConflict(
            Flow flow,
            DataAccessException exception
    ) {
        return new WorkflowException(
                "Flow persistence conflict for "
                        + flow.companyId() + ":" + flow.key(),
                exception
        );
    }
}
