package org.cses.flow.infrastructure.repositories.executions.postgres;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.commands.shared.CommandContext;
import org.cses.flow.core.exceptions.shared.WorkflowException;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.infrastructure.repositories.executions.postgres.entries.ExecutionEntry;
import org.cses.flow.infrastructure.repositories.executions.postgres.entries.TaskRunEntry;
import org.cses.flow.infrastructure.repositories.shared.postgres.PostgresAudit;
import org.jooq.DSLContext;
import org.jooq.JSONB;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.flow.gen.flow.Tables.EXECUTIONS;
import static org.flow.gen.flow.Tables.TASK_RUN;

@Singleton
@Requires(
    property = "flow.memory.enabled",
    value = "false",
    defaultValue = "false"
)
public final class ExecutionPostgresRepository
    implements ExecutionRepository {

    private static final String FLUSHED_EXECUTIONS =
        ExecutionPostgresRepository.class.getName() + ".flushed";

    @Override
    public Optional<Execution> findById(
        DSLContext dsl,
        String companyId,
        String executionId
    ) {
        ExecutionEntry entry = dsl.selectFrom(EXECUTIONS)
            .where(EXECUTIONS.COMPANY_ID.eq(companyId))
            .and(EXECUTIONS.ID.eq(executionId))
            .and(EXECUTIONS.DELETED_AT.isNull())
            .fetchOne(ExecutionEntry::fromRecord);
        if (entry == null) {
            return Optional.empty();
        }
        List<TaskRun> taskRuns = dsl.selectFrom(TASK_RUN)
            .where(TASK_RUN.EXECUTION_ID.eq(executionId))
            .and(TASK_RUN.DELETED_AT.isNull())
            .orderBy(TASK_RUN.ORDER.asc())
            .fetch(TaskRunEntry::fromRecord)
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
            .and(EXECUTIONS.DELETED_AT.isNull())
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
            .and(EXECUTIONS.DELETED_AT.isNull())
            .fetchOne(0, long.class);
    }

    @Override
    public void save(DSLContext dsl, Execution execution) {
        ExecutionEntry stored = dsl.selectFrom(EXECUTIONS)
            .where(EXECUTIONS.COMPANY_ID.eq(execution.companyId()))
            .and(EXECUTIONS.ID.eq(execution.id()))
            .forUpdate()
            .fetchOne(ExecutionEntry::fromRecord);
        List<TaskRunEntry> storedTaskRuns = dsl.selectFrom(TASK_RUN)
            .where(TASK_RUN.EXECUTION_ID.eq(execution.id()))
            .fetch(TaskRunEntry::fromRecord);
        Map<String, TaskRunEntry> storedTaskRunsById =
            new LinkedHashMap<>();
        storedTaskRuns.forEach(taskRun ->
            storedTaskRunsById.put(taskRun.id, taskRun)
        );

        OffsetDateTime now = PostgresAudit.now();
        JSONB actor = PostgresAudit.currentActor(dsl);
        String identity = execution.companyId() + ":" + execution.id();
        boolean alreadyFlushed = flushedExecutions(dsl).contains(identity);

        if (stored == null) {
            if (execution.lockVersion() != 0 || alreadyFlushed) {
                throw conflict(execution, -1);
            }
            ExecutionEntry entry = ExecutionEntry.fromDomain(
                execution,
                actor,
                actor,
                now,
                now
            );
            dsl.insertInto(EXECUTIONS)
                .set(entry.buildInsertMap())
                .execute();
        } else {
            long storedVersion = stored.lockVersion == null
                ? 0
                : stored.lockVersion;
            long expectedVersion = alreadyFlushed
                ? storedVersion
                : storedVersion + 1;
            if (execution.lockVersion() != expectedVersion) {
                throw conflict(execution, storedVersion);
            }
            ExecutionEntry entry = ExecutionEntry.fromDomain(
                execution,
                stored.creator,
                actor,
                stored.createdAt,
                now
            );
            int updated = dsl.update(EXECUTIONS)
                .set(entry.buildUpdateMap())
                .where(EXECUTIONS.COMPANY_ID.eq(execution.companyId()))
                .and(EXECUTIONS.ID.eq(execution.id()))
                .and(EXECUTIONS.LOCK_VERSION.eq(storedVersion))
                .execute();
            if (updated != 1) {
                throw conflict(execution, storedVersion);
            }
        }

        replaceTaskRuns(
            dsl,
            execution,
            storedTaskRunsById,
            now
        );
        flushedExecutions(dsl).add(identity);
    }

    private static void replaceTaskRuns(
        DSLContext dsl,
        Execution execution,
        Map<String, TaskRunEntry> storedTaskRuns,
        OffsetDateTime now
    ) {
        dsl.deleteFrom(TASK_RUN)
            .where(TASK_RUN.EXECUTION_ID.eq(execution.id()))
            .execute();
        List<TaskRun> taskRuns = execution.taskRuns();
        for (int index = 0; index < taskRuns.size(); index++) {
            TaskRun taskRun = taskRuns.get(index);
            TaskRunEntry stored = storedTaskRuns.get(taskRun.id());
            TaskRunEntry entry = TaskRunEntry.fromDomain(
                execution.id(),
                taskRun,
                index,
                stored == null ? now : stored.createdAt,
                now
            );
            dsl.insertInto(TASK_RUN)
                .set(entry.buildInsertMap())
                .execute();
        }
    }

    private static Set<String> flushedExecutions(DSLContext dsl) {
        Object scope = dsl.configuration().data(CommandContext.class);
        if (scope == null) {
            scope = dsl.configuration();
        }
        Object existing = dsl.configuration().data(FLUSHED_EXECUTIONS);
        if (existing instanceof FlushState state
            && state.scope == scope) {
            return state.identities;
        }
        FlushState created = new FlushState(scope);
        dsl.configuration().data(FLUSHED_EXECUTIONS, created);
        return created.identities;
    }

    private static WorkflowException conflict(
        Execution execution,
        long storedVersion
    ) {
        return new WorkflowException(
            "Execution lock conflict for " + execution.id()
                + ": stored " + storedVersion
                + ", attempted " + execution.lockVersion()
        );
    }

    private static final class FlushState {

        private final Object scope;
        private final Set<String> identities = new HashSet<>();

        private FlushState(Object scope) {
            this.scope = scope;
        }
    }
}
