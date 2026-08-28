package org.cses.flow.infrastructure.repositories.flows;

import jakarta.inject.Singleton;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowId;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.extensions.flow.Branch;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.infrastructure.repositories.flows.entries.FlowEntry;
import org.cses.flow.infrastructure.repositories.flows.entries.FlowTaskEntry;
import org.flow.gen.flow.records.FlowTasksRecord;
import org.jooq.DSLContext;
import org.jooq.InsertValuesStepN;
import org.jooq.exception.DataAccessException;
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

    @Override
    public List<Flow> findDrafts(
            DSLContext dsl,
            String companyId
    ) {
        return dsl.select()
                .from(FLOWS)
                .where(FLOWS.COMPANY_ID.eq(companyId))
                .and(FLOWS.DRAFT.eq(true))
                .and(FLOWS.STATUS.ne(RecordState.Delete.getName()))
                .orderBy(FLOWS.UPDATED_AT.desc(), FLOWS.ID.asc())
                .fetchInto(FlowEntry.class)
                .stream()
                .map(FlowEntry::to)
                .toList();
    }

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
                .and(FLOWS.STATUS.ne(RecordState.Delete.getName()))
                .fetchOneInto(FlowEntry.class);
        return Optional.ofNullable(entry).map(FlowEntry::to);
    }

    @Override
    public void save(DSLContext dsl, Flow flow) {
        FlowEntry entry = FlowEntry.from(flow);
        try {
            int updated = dsl.update(FLOWS)
                    .set(entry.buildUpdateMap())
                    .where(FLOWS.COMPANY_ID.eq(entry.companyId))
                    .and(FLOWS.ID.eq(entry.id))
                    .execute();
            if (updated == 1) {
                return;
            }
            if (updated != 0) {
                throw new WorkflowException(
                        "Flow update affected unexpected row count: "
                                + updated
                );
            }
            insert(dsl, flow, entry);
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

    private void insert(
            DSLContext dsl,
            Flow flow,
            FlowEntry entry
    ) {
        insert(dsl, entry);
        if (flow.deployed()) {
            writeTasks(dsl, flow);
        }
    }

    private void insert(DSLContext dsl, FlowEntry entry) {
        dsl.insertInto(FLOWS)
                .set(entry.buildInsertMap())
                .execute();
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
                .orderBy(FLOW_TASKS.ORDER.asc())
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
                .sorted(Comparator.comparingInt(entry -> entry.order))
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

    private void writeTasks(DSLContext dsl, Flow flow) {
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
                flow.version(),
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

    private static WorkflowException persistenceConflict(
            Flow flow,
            DataAccessException exception
    ) {
        String subject = flow.draft()
                ? flow.companyId() + ":" + flow.key()
                : flow.key() + ":" + flow.version();
        return new WorkflowException(
                "Flow persistence conflict for " + subject,
                exception
        );
    }
}
