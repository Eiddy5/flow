package org.cses.flow.infrastructure.repositories.externaltasks.postgres;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.externaltasks.ExternalTask;
import org.cses.flow.core.domains.externaltasks.ExternalTaskStatus;
import org.cses.flow.core.exceptions.shared.WorkflowException;
import org.cses.flow.core.repositories.externaltasks.ExternalTaskRepository;
import org.cses.flow.infrastructure.repositories.externaltasks.postgres.entries.ExternalTaskEntry;
import org.cses.flow.infrastructure.repositories.shared.postgres.PostgresAudit;
import org.jooq.DSLContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.flow.gen.flow.Tables.ASSIGNMENT;

@Singleton
@Requires(
    property = "flow.memory.enabled",
    value = "false",
    defaultValue = "false"
)
public final class ExternalTaskPostgresRepository
    implements ExternalTaskRepository {

    @Override
    public Optional<ExternalTask> findById(
        DSLContext dsl,
        String companyId,
        String externalTaskId
    ) {
        ExternalTaskEntry entry = dsl.selectFrom(ASSIGNMENT)
            .where(ASSIGNMENT.COMPANY_ID.eq(companyId))
            .and(ASSIGNMENT.ID.eq(externalTaskId))
            .fetchOne(ExternalTaskEntry::fromRecord);
        return entry == null
            ? Optional.empty()
            : Optional.of(entry.toDomain());
    }

    @Override
    public Optional<ExternalTask> findWaitingByTaskRunId(
        DSLContext dsl,
        String companyId,
        String taskRunId
    ) {
        ExternalTaskEntry entry = dsl.selectFrom(ASSIGNMENT)
            .where(ASSIGNMENT.COMPANY_ID.eq(companyId))
            .and(ASSIGNMENT.TASK_RUN_ID.eq(taskRunId))
            .and(ASSIGNMENT.STATUS.eq(ExternalTaskStatus.WAITING.name()))
            .fetchOne(ExternalTaskEntry::fromRecord);
        return entry == null
            ? Optional.empty()
            : Optional.of(entry.toDomain());
    }

    @Override
    public List<ExternalTask> findWaiting(
        DSLContext dsl,
        String companyId
    ) {
        return dsl.selectFrom(ASSIGNMENT)
            .where(ASSIGNMENT.COMPANY_ID.eq(companyId))
            .and(ASSIGNMENT.STATUS.eq(ExternalTaskStatus.WAITING.name()))
            .orderBy(
                ASSIGNMENT.CREATED_AT.asc(),
                ASSIGNMENT.ID.asc()
            )
            .fetch(ExternalTaskEntry::fromRecord)
            .stream()
            .map(ExternalTaskEntry::toDomain)
            .toList();
    }

    @Override
    public void save(DSLContext dsl, ExternalTask externalTask) {
        ExternalTaskEntry stored = dsl.selectFrom(ASSIGNMENT)
            .where(ASSIGNMENT.COMPANY_ID.eq(externalTask.companyId()))
            .and(ASSIGNMENT.ID.eq(externalTask.id()))
            .forUpdate()
            .fetchOne(ExternalTaskEntry::fromRecord);
        OffsetDateTime now = PostgresAudit.now();
        if (stored == null) {
            if (externalTask.lockVersion() != 0) {
                throw conflict(externalTask, -1);
            }
            ExternalTaskEntry entry = ExternalTaskEntry.fromDomain(
                externalTask,
                now,
                now
            );
            dsl.insertInto(ASSIGNMENT)
                .set(entry.buildInsertMap())
                .execute();
            return;
        }

        long expected = stored.lockVersion == null ? 0 : stored.lockVersion;
        if (externalTask.lockVersion() != expected + 1) {
            throw conflict(externalTask, expected);
        }
        ExternalTaskEntry entry = ExternalTaskEntry.fromDomain(
            externalTask,
            stored.createdAt,
            now
        );
        int updated = dsl.update(ASSIGNMENT)
            .set(entry.buildUpdateMap())
            .where(ASSIGNMENT.COMPANY_ID.eq(externalTask.companyId()))
            .and(ASSIGNMENT.ID.eq(externalTask.id()))
            .and(ASSIGNMENT.LOCK_VERSION.eq(expected))
            .execute();
        if (updated != 1) {
            throw conflict(externalTask, expected);
        }
    }

    private static WorkflowException conflict(
        ExternalTask task,
        long storedVersion
    ) {
        return new WorkflowException(
            "ExternalTask lock conflict for " + task.id()
                + ": stored " + storedVersion
                + ", attempted " + task.lockVersion()
        );
    }
}
