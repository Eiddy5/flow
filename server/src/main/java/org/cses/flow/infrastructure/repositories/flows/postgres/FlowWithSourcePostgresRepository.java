package org.cses.flow.infrastructure.repositories.flows.postgres;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.flows.FlowWithSource;
import org.cses.flow.core.exceptions.shared.WorkflowException;
import org.cses.flow.core.repositories.flows.FlowWithSourceRepository;
import org.cses.flow.infrastructure.repositories.flows.postgres.entries.FlowWithSourceEntry;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;

import java.util.Optional;

import static org.flow.gen.flow.Tables.FLOW_DRAFTS;

@Singleton
@Requires(
    property = "flow.memory.enabled",
    value = "false",
    defaultValue = "false"
)
public final class FlowWithSourcePostgresRepository
    implements FlowWithSourceRepository {

    @Override
    public Optional<FlowWithSource> findById(
        DSLContext dsl,
        String companyId,
        String flowId
    ) {
        FlowWithSourceEntry entry = dsl.selectFrom(FLOW_DRAFTS)
            .where(FLOW_DRAFTS.COMPANY_ID.eq(companyId))
            .and(FLOW_DRAFTS.ID.eq(flowId))
            .and(FLOW_DRAFTS.DELETED_AT.isNull())
            .fetchOne(FlowWithSourceEntry::fromRecord);
        return entry == null
            ? Optional.empty()
            : Optional.of(entry.toDomain());
    }

    @Override
    public Optional<FlowWithSource> lockById(
        DSLContext dsl,
        String companyId,
        String flowId
    ) {
        FlowWithSourceEntry entry = dsl.selectFrom(FLOW_DRAFTS)
            .where(FLOW_DRAFTS.COMPANY_ID.eq(companyId))
            .and(FLOW_DRAFTS.ID.eq(flowId))
            .and(FLOW_DRAFTS.DELETED_AT.isNull())
            .forUpdate()
            .fetchOne(FlowWithSourceEntry::fromRecord);
        return entry == null
            ? Optional.empty()
            : Optional.of(entry.toDomain());
    }

    @Override
    public void save(DSLContext dsl, FlowWithSource source) {
        FlowWithSourceEntry stored = dsl.selectFrom(FLOW_DRAFTS)
            .where(FLOW_DRAFTS.COMPANY_ID.eq(source.companyId()))
            .and(FLOW_DRAFTS.ID.eq(source.id()))
            .forUpdate()
            .fetchOne(FlowWithSourceEntry::fromRecord);
        FlowWithSourceEntry entry = FlowWithSourceEntry.fromDomain(source);
        if (stored == null) {
            insert(dsl, source, entry);
            return;
        }

        long storedVersion = stored.lockVersion == null
            ? 0
            : stored.lockVersion;
        if (source.lockVersion() != storedVersion + 1) {
            throw conflict(source, storedVersion);
        }
        int updated = dsl.update(FLOW_DRAFTS)
            .set(entry.buildUpdateMap())
            .where(FLOW_DRAFTS.COMPANY_ID.eq(source.companyId()))
            .and(FLOW_DRAFTS.ID.eq(source.id()))
            .and(FLOW_DRAFTS.LOCK_VERSION.eq(storedVersion))
            .execute();
        if (updated != 1) {
            throw conflict(source, storedVersion);
        }
    }

    private static void insert(
        DSLContext dsl,
        FlowWithSource source,
        FlowWithSourceEntry entry
    ) {
        if (source.lockVersion() != 0) {
            throw conflict(source, -1);
        }
        try {
            dsl.insertInto(FLOW_DRAFTS)
                .set(entry.buildInsertMap())
                .execute();
        } catch (DataAccessException exception) {
            throw new WorkflowException(
                "FlowWithSource identity conflict for " + source.id(),
                exception
            );
        }
    }

    private static WorkflowException conflict(
        FlowWithSource source,
        long storedVersion
    ) {
        return new WorkflowException(
            "FlowWithSource lock conflict for " + source.id()
                + ": stored " + storedVersion
                + ", attempted " + source.lockVersion()
        );
    }
}
