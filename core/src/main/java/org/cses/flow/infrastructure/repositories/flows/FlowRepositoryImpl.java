package org.cses.flow.infrastructure.repositories.flows;

import jakarta.inject.Singleton;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowId;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.extensions.flow.Branch;
import org.cses.flow.infrastructure.repositories.flows.entries.FlowEntry;
import org.cses.flow.infrastructure.repositories.flows.entries.FlowTaskEntry;
import org.flow.gen.flow.records.FlowTasksRecord;
import org.flow.gen.flow.tables.FlowsTable;
import org.jooq.DSLContext;
import org.jooq.InsertValuesStepN;
import org.jooq.exception.DataAccessException;
import org.paas.common.util.StringUtil;
import org.paas.session.RecordState;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.flow.gen.flow.Tables.FLOWS;
import static org.flow.gen.flow.Tables.FLOW_TASKS;

@Singleton
public class FlowRepositoryImpl implements FlowRepository {

    @Override
    public Optional<Flow> findById(
            DSLContext dsl,
            String companyId,
            String id
    ) {
        FlowEntry entry = dsl.select()
                .from(FLOWS)
                .where(FLOWS.COMPANY_ID.eq(companyId))
                .and(FLOWS.ID.eq(id))
                .fetchOneInto(FlowEntry.class);
        return restore(dsl, entry);
    }

    @Override
    public Optional<Flow> findByFlowId(
            DSLContext dsl,
            FlowId flowId
    ) {
        long flowVersion = requireVersion(flowId);
        FlowEntry entry = dsl.select()
                .from(FLOWS)
                .where(FLOWS.COMPANY_ID.eq(flowId.companyId()))
                .and(FLOWS.KEY.eq(flowId.key()))
                .and(FLOWS.DRAFT.eq(false))
                .and(FLOWS.VERSION.eq(flowVersion))
                .fetchOneInto(FlowEntry.class);
        return restore(dsl, entry);
    }

    @Override
    public Optional<Flow> findLatestByFlowId(
            DSLContext dsl,
            FlowId flowId
    ) {
        requireLogicalFlow(flowId);
        FlowEntry entry = dsl.select()
                .from(FLOWS)
                .where(FLOWS.COMPANY_ID.eq(flowId.companyId()))
                .and(FLOWS.KEY.eq(flowId.key()))
                .and(FLOWS.DRAFT.eq(false))
                .orderBy(FLOWS.VERSION.desc())
                .limit(1)
                .fetchOneInto(FlowEntry.class);
        return restore(dsl, entry);
    }

    /**
     * Lists only the latest active draft row for each tenant-scoped Flow key.
     *
     * @param dsl non-null caller-owned database context used only for reads
     * @param companyId non-blank tenant whose drafts are listed
     * @return an unmodifiable list of detached latest active drafts ordered by
     *         update time, or an empty list when none exist
     */
    @Override
    public List<Flow> findDrafts(
            DSLContext dsl,
            String companyId
    ) {
        FlowsTable newer = FLOWS.as("newer_draft");
        return dsl.select()
                .from(FLOWS)
                .where(FLOWS.COMPANY_ID.eq(companyId))
                .and(FLOWS.DRAFT.eq(true))
                .andNotExists(dsl.selectOne()
                        .from(newer)
                        .where(newer.COMPANY_ID.eq(FLOWS.COMPANY_ID))
                        .and(newer.KEY.eq(FLOWS.KEY))
                        .and(newer.DRAFT.eq(true))
                        .and(newer.VERSION.gt(FLOWS.VERSION)))
                .and(FLOWS.STATUS.ne(RecordState.Delete.getName()))
                .orderBy(FLOWS.UPDATED_AT.desc(), FLOWS.ID.asc())
                .fetchInto(FlowEntry.class)
                .stream()
                .map(FlowEntry::to)
                .toList();
    }

    /**
     * Loads the latest draft row and suppresses it when that row is deleted.
     *
     * @param dsl non-null caller-owned database context used only for reads
     * @param flowId non-null tenant and Flow key selector without a version
     * @return the latest active draft, or empty when absent or deleted
     * @throws IllegalArgumentException when the selector contains a version
     */
    @Override
    public Optional<Flow> findDraftByFlowId(
            DSLContext dsl,
            FlowId flowId
    ) {
        requireLogicalFlow(flowId);
        FlowEntry entry = dsl.select()
                .from(FLOWS)
                .where(FLOWS.COMPANY_ID.eq(flowId.companyId()))
                .and(FLOWS.KEY.eq(flowId.key()))
                .and(FLOWS.DRAFT.eq(true))
                .orderBy(FLOWS.VERSION.desc())
                .limit(1)
                .fetchOneInto(FlowEntry.class);
        return Optional.ofNullable(entry)
                .map(FlowEntry::to)
                .filter(flow -> !flow.deleted());
    }

    /**
     * Appends a Flow row after assigning the next version across all rows for
     * the same tenant and key. Deployed rows also append their Task snapshot.
     *
     * @param dsl ordinary database context; the parent and Task inserts share one SQL statement
     * @param flow non-null Flow whose definition and domain-owned tenant,
     *        status and audit facts are read without modifying the object
     * @return a detached Flow containing the assigned row id and version; its
     *         mutable containers are independent of the input Flow
     * @throws WorkflowException when the database rejects the appended rows
     * @throws ArithmeticException when the stored version is
     *         {@link Long#MAX_VALUE}
     */
    @Override
    public Flow save(DSLContext dsl, Flow flow) {
        try {
            Long latest = dsl.select(FLOWS.VERSION)
                    .from(FLOWS)
                    .where(FLOWS.COMPANY_ID.eq(flow.companyId()))
                    .and(FLOWS.KEY.eq(flow.key()))
                    .orderBy(FLOWS.VERSION.desc())
                    .limit(1)
                    .fetchOne(FLOWS.VERSION);
            long version = latest == null
                    ? 1
                    : Math.addExact(latest, 1);
            FlowEntry entry = FlowEntry.from(
                    flow,
                    StringUtil.newId(),
                    version
            );
            var parent = org.jooq.impl.DSL.name("stored_flow").as(
                    dsl.insertInto(FLOWS).set(entry.toMap()).returning(FLOWS.ID));
            if (flow.deployed() && !flow.tasks().isEmpty()) {
                writeTasks(dsl.with(parent), flow, version);
            } else {
                dsl.with(parent).selectFrom(parent).fetch();
            }
            return entry.to(flow.tasks());
        } catch (DataAccessException exception) {
            throw persistenceConflict(flow, exception);
        }
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
        List<FlowTaskEntry> entries = dsl.select()
                .from(FLOW_TASKS)
                .where(FLOW_TASKS.COMPANY_ID.eq(companyId))
                .and(FLOW_TASKS.FLOW_KEY.eq(flowKey))
                .and(FLOW_TASKS.FLOW_VERSION.eq(flowVersion))
                .orderBy(FLOW_TASKS.POSITION.asc())
                .fetchInto(FlowTaskEntry.class);
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
     * Appends the Task snapshot for one deployed Flow version.
     *
     * @param dsl pending statement containing the complete parent insert
     * @param flow non-null deployed Flow whose immutable Task tree is read
     * @param flowVersion positive Repository-assigned Flow version
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
