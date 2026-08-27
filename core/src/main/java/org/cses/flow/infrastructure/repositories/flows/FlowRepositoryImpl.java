package org.cses.flow.infrastructure.repositories.flows;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowId;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.serializers.JacksonMapper;
import org.cses.flow.infrastructure.repositories.flows.entries.FlowEntry;
import org.cses.flow.infrastructure.repositories.flows.entries.FlowTaskEntry;
import org.flow.gen.flow.records.FlowTasksRecord;
import org.jooq.DSLContext;
import org.jooq.InsertValuesStepN;
import org.jooq.SelectConditionStep;
import org.jooq.exception.DataAccessException;
import org.paas.session.RecordState;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.flow.gen.flow.Tables.FLOW_TASKS;
import static org.flow.gen.flow.Tables.FLOWS;

@Singleton
public class FlowRepositoryImpl implements FlowRepository {

    private JacksonMapper jacksonMapper;

    @Inject
    public FlowRepositoryImpl(JacksonMapper jacksonMapper) {
        this.jacksonMapper = jacksonMapper;
    }

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
        return optionalDomain(dsl, entry);
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
        return optionalDomain(dsl, entry);
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
        return optionalDomain(dsl, entry);
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
                .map(entry -> entry.toDomain(List.of()))
                .toList();
    }

    @Override
    public Optional<Flow> findDraftByFlowId(
            DSLContext dsl,
            FlowId flowId
    ) {
        requireLogicalFlow(flowId);
        FlowEntry entry = draftQuery(dsl, flowId.companyId(), flowId.key())
                .fetchOneInto(FlowEntry.class);
        return entry == null
                ? Optional.empty()
                : Optional.of(entry.toDomain(List.of()));
    }

    @Override
    public void save(DSLContext dsl, Flow flow) {
        FlowEntry stored = dsl.select()
                .from(FLOWS)
                .where(FLOWS.COMPANY_ID.eq(flow.companyId()))
                .and(FLOWS.ID.eq(flow.id()))
                .fetchOneInto(FlowEntry.class);
        if (stored == null) {
            if (flow.draft()) {
                insertDraft(dsl, flow);
            } else {
                insertReversion(dsl, flow);
            }
            return;
        }
        if (flow.draft()) {
            updateDraft(dsl, stored, flow);
        } else {
            updateDeployedAudit(dsl, stored, flow);
        }
    }

    private SelectConditionStep<?> draftQuery(
            DSLContext dsl,
            String companyId,
            String flowKey
    ) {
        return dsl.select()
                .from(FLOWS)
                .where(FLOWS.COMPANY_ID.eq(companyId))
                .and(FLOWS.KEY.eq(flowKey))
                .and(FLOWS.DRAFT.eq(true))
                .and(FLOWS.STATUS.ne(RecordState.Delete.getName()));
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

    private Optional<Flow> optionalDomain(
            DSLContext dsl,
            FlowEntry entry
    ) {
        return entry == null
                ? Optional.empty()
                : Optional.of(toDomain(dsl, entry));
    }

    private void insertDraft(DSLContext dsl, Flow draft) {
        try {
            dsl.insertInto(FLOWS)
                    .set(FlowEntry.fromDomain(draft).buildInsertMap())
                    .execute();
        } catch (DataAccessException exception) {
            throw new WorkflowException(
                    "Draft Flow key conflict for "
                            + draft.companyId() + ":" + draft.key(),
                    exception
            );
        }
    }

    private void updateDraft(
            DSLContext dsl,
            FlowEntry storedEntry,
            Flow draft
    ) {
        if (!Boolean.TRUE.equals(storedEntry.draft)) {
            throw stateConflict(draft);
        }
        Flow stored = storedEntry.toDomain(List.of());
        requireAllowedDraftChange(stored, draft);
        int updated = dsl.update(FLOWS)
                .set(FlowEntry.fromDomain(draft).buildUpdateMap())
                .where(FLOWS.COMPANY_ID.eq(draft.companyId()))
                .and(FLOWS.ID.eq(draft.id()))
                .and(FLOWS.DRAFT.eq(true))
                .and(FLOWS.STATUS.ne(RecordState.Delete.getName()))
                .execute();
        if (updated != 1) {
            throw new WorkflowException(
                    "Draft Flow was not updated: " + draft.id()
            );
        }
    }

    private void insertReversion(DSLContext dsl, Flow flow) {
        FlowEntry latest = dsl.select()
                .from(FLOWS)
                .where(FLOWS.COMPANY_ID.eq(flow.companyId()))
                .and(FLOWS.KEY.eq(flow.key()))
                .and(FLOWS.DRAFT.eq(false))
                .orderBy(FLOWS.VERSION.desc())
                .limit(1)
                .fetchOneInto(FlowEntry.class);
        long latestReversion = latest == null ? 0 : latest.version;
        if (flow.reversion() != latestReversion + 1) {
            throw reversionConflict(flow, latestReversion);
        }
        if (latest != null
                && RecordState.Delete.getName().equals(latest.status)) {
            throw new WorkflowException(
                    "Deleted Flow cannot receive a new version: " + flow.key()
            );
        }
        if (!RecordState.Open.equals(flow.status())) {
            throw new WorkflowException(
                    "A new Flow reversion must have Open audit status: "
                            + flow.key() + ":" + flow.reversion()
            );
        }
        try {
            dsl.insertInto(FLOWS)
                    .set(FlowEntry.fromDomain(flow).buildInsertMap())
                    .execute();
            insertTasks(dsl, flow);
        } catch (DataAccessException exception) {
            throw new WorkflowException(
                    "Flow reversion conflict for "
                            + flow.key() + ":" + flow.reversion(),
                    exception
            );
        }
    }

    private void updateDeployedAudit(
            DSLContext dsl,
            FlowEntry storedEntry,
            Flow flow
    ) {
        if (Boolean.TRUE.equals(storedEntry.draft)) {
            throw stateConflict(flow);
        }
        Flow stored = storedEntry.toDomain(loadTasks(
                dsl,
                flow.companyId(),
                flow.key(),
                flow.reversion()
        ));
        requireAuditOnlyChange(stored, flow);
        int updated = dsl.update(FLOWS)
                .set(FlowEntry.fromDomain(flow).buildUpdateMap())
                .where(FLOWS.COMPANY_ID.eq(flow.companyId()))
                .and(FLOWS.ID.eq(flow.id()))
                .and(FLOWS.DRAFT.eq(false))
                .and(FLOWS.STATUS.ne(RecordState.Delete.getName()))
                .execute();
        if (updated != 1) {
            throw new WorkflowException(
                    "Flow audit was not updated: " + flow.id()
            );
        }
    }

    private Flow toDomain(DSLContext dsl, FlowEntry entry) {
        if (Boolean.TRUE.equals(entry.draft)) {
            return entry.toDomain(List.of());
        }
        return entry.toDomain(loadTasks(
                dsl,
                entry.companyId,
                entry.key,
                entry.version
        ));
    }

    private List<Task> loadTasks(
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
        List<Task> tasks = restoreChildren(entries, null, restored);
        if (restored.size() != entries.size()) {
            throw new IllegalStateException(
                    "Persisted Flow Task tree has orphan or cyclic rows for "
                            + flowKey + ":" + flowVersion
            );
        }
        return tasks;
    }

    private List<Task> restoreChildren(
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
                    return entry.toDomain(
                            jacksonMapper,
                            restoreChildren(entries, entry.id, restored)
                    );
                })
                .toList();
    }

    private void insertTasks(DSLContext dsl, Flow flow) {
        if (flow.tasks().isEmpty()) {
            return;
        }
        InsertValuesStepN<FlowTasksRecord> values = dsl
                .insertInto(FLOW_TASKS)
                .columns();
        appendTasks(
                values,
                flow.companyId(),
                flow.key(),
                flow.reversion(),
                null,
                flow.tasks()
        );
        values.execute();
    }

    private void appendTasks(
            InsertValuesStepN<FlowTasksRecord> values,
            String companyId,
            String flowKey,
            long flowVersion,
            String parentId,
            List<Task> tasks
    ) {
        for (int index = 0; index < tasks.size(); index++) {
            Task task = tasks.get(index);
            values.values(FlowTaskEntry.fromDomain(
                    companyId,
                    flowKey,
                    flowVersion,
                    task,
                    parentId,
                    index,
                    jacksonMapper
            ).toRecord());
            appendTasks(
                    values,
                    companyId,
                    flowKey,
                    flowVersion,
                    task.id(),
                    task.tasks()
            );
        }
    }

    private static void requireAllowedDraftChange(
            Flow stored,
            Flow attempted
    ) {
        boolean identityAndCreationAuditMatch =
                stored.id().equals(attempted.id())
                        && stored.companyId().equals(attempted.companyId())
                        && stored.key().equals(attempted.key())
                        && stored.draft() == attempted.draft()
                        && Objects.equals(
                        stored.versionOrNull(),
                        attempted.versionOrNull()
                )
                        && stored.creator().equals(attempted.creator())
                        && stored.createdAt() == attempted.createdAt();
        if (!identityAndCreationAuditMatch || stored.deleted()) {
            throw new WorkflowException(
                    "Draft Flow update violates lifecycle invariants: "
                            + attempted.id()
            );
        }
        if (!stored.status().equals(attempted.status())
                && !sameDefinition(stored, attempted)) {
            throw new WorkflowException(
                    "Changing draft Flow audit status must not change its "
                            + "definition: " + attempted.id()
            );
        }
    }

    private static void requireAuditOnlyChange(
            Flow stored,
            Flow attempted
    ) {
        if (stored.deleted()) {
            throw new WorkflowException(
                    "Deleted Flow audit cannot be changed: " + attempted.id()
            );
        }
        boolean immutableStateMatches =
                stored.id().equals(attempted.id())
                        && stored.companyId().equals(attempted.companyId())
                        && stored.key().equals(attempted.key())
                        && stored.draft() == attempted.draft()
                        && Objects.equals(
                        stored.versionOrNull(),
                        attempted.versionOrNull()
                )
                        && sameDefinition(stored, attempted)
                        && stored.tasks().equals(attempted.tasks())
                        && stored.creator().equals(attempted.creator())
                        && stored.createdAt() == attempted.createdAt();
        if (!immutableStateMatches) {
            throw new WorkflowException(
                    "Changing Flow audit must not change its deployed definition: "
                            + attempted.id()
            );
        }
        if (stored.status().equals(attempted.status())) {
            throw new WorkflowException(
                    "Existing Flow save must change its audit status: "
                            + attempted.id()
            );
        }
    }

    private static boolean sameDefinition(
            Flow left,
            Flow right
    ) {
        return left.description().equals(right.description())
                && left.variables().equals(right.variables())
                && left.inputs().equals(right.inputs())
                && left.outputs().equals(right.outputs())
                && left.source().equals(right.source());
    }

    private static WorkflowException stateConflict(Flow flow) {
        return new WorkflowException(
                "Flow state cannot change between draft and deployed: "
                        + flow.id()
        );
    }

    private static WorkflowException reversionConflict(
            Flow flow,
            long storedReversion
    ) {
        return new WorkflowException(
                "Flow version conflict for " + flow.key()
                        + ": stored latest " + storedReversion
                        + ", attempted " + flow.reversion()
        );
    }
}
