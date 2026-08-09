package org.cses.flow.infrastructure.repositories.flows.postgres;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.serializers.JacksonMapper;
import org.cses.flow.infrastructure.repositories.flows.postgres.entries.FlowEntry;
import org.cses.flow.infrastructure.repositories.flows.postgres.entries.FlowTaskEntry;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.flow.gen.flow.Tables.FLOW_TASKS;
import static org.flow.gen.flow.Tables.FLOWS;

@Singleton
@Requires(
    property = "flow.memory.enabled",
    value = "false",
    defaultValue = "false"
)
public final class FlowPostgresRepository implements FlowRepository {

    private final JacksonMapper jacksonMapper;

    @Inject
    public FlowPostgresRepository(
        JacksonMapper jacksonMapper
    ) {
        this.jacksonMapper = jacksonMapper;
    }

    @Override
    public Optional<Flow> findById(
        DSLContext dsl,
        String companyId,
        String flowId,
        long reversion
    ) {
        FlowEntry entry = dsl.selectFrom(FLOWS)
            .where(FLOWS.COMPANY_ID.eq(companyId))
            .and(FLOWS.ID.eq(flowId))
            .and(FLOWS.REVERSION.eq(reversion))
            .fetchOne(FlowEntry::fromRecord);
        return entry == null
            ? Optional.empty()
            : Optional.of(toDomain(dsl, entry));
    }

    @Override
    public Optional<Flow> findLatest(
        DSLContext dsl,
        String companyId,
        String flowId
    ) {
        FlowEntry entry = dsl.selectFrom(FLOWS)
            .where(FLOWS.COMPANY_ID.eq(companyId))
            .and(FLOWS.ID.eq(flowId))
            .orderBy(FLOWS.REVERSION.desc())
            .limit(1)
            .fetchOne(FlowEntry::fromRecord);
        return entry == null
            ? Optional.empty()
            : Optional.of(toDomain(dsl, entry));
    }

    @Override
    public void save(DSLContext dsl, Flow flow) {
        FlowEntry storedEntry = dsl.selectFrom(FLOWS)
            .where(FLOWS.COMPANY_ID.eq(flow.companyId()))
            .and(FLOWS.ID.eq(flow.id()))
            .and(FLOWS.REVERSION.eq(flow.reversion()))
            .forUpdate()
            .fetchOne(FlowEntry::fromRecord);
        if (storedEntry == null) {
            insertReversion(dsl, flow);
            return;
        }
        deleteReversion(dsl, storedEntry, flow);
    }

    private void insertReversion(DSLContext dsl, Flow flow) {
        FlowEntry latest = dsl.selectFrom(FLOWS)
            .where(FLOWS.COMPANY_ID.eq(flow.companyId()))
            .and(FLOWS.ID.eq(flow.id()))
            .orderBy(FLOWS.REVERSION.desc())
            .limit(1)
            .forUpdate()
            .fetchOne(FlowEntry::fromRecord);
        long latestReversion = latest == null ? 0 : latest.reversion;
        if (flow.reversion() != latestReversion + 1) {
            throw reversionConflict(flow, latestReversion);
        }
        if (latest != null && Boolean.TRUE.equals(latest.deleted)) {
            throw new WorkflowException(
                "Deleted Flow cannot receive a new reversion: " + flow.id()
            );
        }
        if (flow.isDeleted()) {
            throw new WorkflowException(
                "A new Flow reversion must be undeleted: "
                    + flow.id() + ":" + flow.reversion()
            );
        }

        try {
            FlowEntry entry = FlowEntry.fromDomain(flow);
            dsl.insertInto(FLOWS)
                .set(entry.buildInsertMap())
                .execute();
            insertTasks(dsl, flow);
        } catch (DataAccessException exception) {
            throw new WorkflowException(
                "Flow reversion conflict for "
                    + flow.id() + ":" + flow.reversion(),
                exception
            );
        }
    }

    private void deleteReversion(
        DSLContext dsl,
        FlowEntry storedEntry,
        Flow flow
    ) {
        Flow stored = storedEntry.toDomain(loadTasks(
            dsl,
            flow.companyId(),
            flow.id(),
            flow.reversion()
        ));
        requireDeleteOnlyChange(stored, flow);
        FlowEntry entry = FlowEntry.fromDomain(flow);
        int updated = dsl.update(FLOWS)
            .set(entry.buildUpdateMap())
            .where(FLOWS.COMPANY_ID.eq(flow.companyId()))
            .and(FLOWS.ID.eq(flow.id()))
            .and(FLOWS.REVERSION.eq(flow.reversion()))
            .and(FLOWS.DELETED.eq(false))
            .execute();
        if (updated != 1) {
            throw new WorkflowException(
                "Flow delete conflict for "
                    + flow.id() + ":" + flow.reversion()
            );
        }
    }

    private Flow toDomain(DSLContext dsl, FlowEntry entry) {
        return entry.toDomain(loadTasks(
            dsl,
            entry.companyId,
            entry.id,
            entry.reversion
        ));
    }

    private List<Task> loadTasks(
        DSLContext dsl,
        String companyId,
        String flowId,
        long reversion
    ) {
        List<FlowTaskEntry> entries = dsl.selectFrom(FLOW_TASKS)
            .where(FLOW_TASKS.COMPANY_ID.eq(companyId))
            .and(FLOW_TASKS.FLOW_ID.eq(flowId))
            .and(FLOW_TASKS.FLOW_REVERSION.eq(reversion))
            .orderBy(FLOW_TASKS.ORDER.asc())
            .fetch(FlowTaskEntry::fromRecord);
        Set<String> restored = new HashSet<>();
        List<Task> tasks = restoreChildren(entries, null, restored);
        if (restored.size() != entries.size()) {
            throw new IllegalStateException(
                "Persisted Flow Task tree has orphan or cyclic rows for "
                    + flowId + ":" + reversion
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
        List<FlowTaskEntry> entries = new ArrayList<>();
        appendTasks(
            entries,
            flow.companyId(),
            flow.id(),
            flow.reversion(),
            null,
            flow.tasks()
        );
        for (FlowTaskEntry entry : entries) {
            dsl.insertInto(FLOW_TASKS)
                .set(entry.buildInsertMap())
                .execute();
        }
    }

    private void appendTasks(
        List<FlowTaskEntry> entries,
        String companyId,
        String flowId,
        long reversion,
        String parentId,
        List<Task> tasks
    ) {
        for (int index = 0; index < tasks.size(); index++) {
            Task task = tasks.get(index);
            entries.add(FlowTaskEntry.fromDomain(
                companyId,
                flowId,
                reversion,
                task,
                parentId,
                index,
                jacksonMapper
            ));
            appendTasks(
                entries,
                companyId,
                flowId,
                reversion,
                task.id(),
                task.tasks()
            );
        }
    }

    private static void requireDeleteOnlyChange(
        Flow stored,
        Flow attempted
    ) {
        if (stored.isDeleted() || !attempted.isDeleted()) {
            throw new WorkflowException(
                "Existing Flow reversion may only transition from "
                    + "deleted=false to deleted=true: " + attempted.id()
                    + ":" + attempted.reversion()
            );
        }
        boolean immutableStateMatches =
            stored.identifiedBy(attempted.identifier())
                && stored.companyId().equals(attempted.companyId())
                && stored.key().equals(attempted.key())
                && stored.reversion() == attempted.reversion()
                && stored.description().equals(attempted.description())
                && stored.inputs().equals(attempted.inputs())
                && stored.outputs().equals(attempted.outputs())
                && stored.tasks().equals(attempted.tasks())
                && stored.creator().equals(attempted.creator())
                && stored.createdAt() == attempted.createdAt();
        if (!immutableStateMatches) {
            throw new WorkflowException(
                "Deleting a Flow must not change its deployed definition: "
                    + attempted.id() + ":" + attempted.reversion()
            );
        }
    }

    private static WorkflowException reversionConflict(
        Flow flow,
        long storedReversion
    ) {
        return new WorkflowException(
            "Flow reversion conflict for " + flow.id()
                + ": stored latest " + storedReversion
                + ", attempted " + flow.reversion()
        );
    }
}
