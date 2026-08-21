package org.cses.flow.infrastructure.repositories.flows.postgres;

import jakarta.inject.Singleton;
import org.cses.flow.core.domains.flows.FlowDraft;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.repositories.flows.FlowDraftRepository;
import org.cses.flow.infrastructure.repositories.flows.postgres.entries.FlowDraftEntry;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;

import java.util.List;
import java.util.Optional;

import static org.flow.gen.flow.Tables.FLOW_DRAFTS;

@Singleton
public final class FlowDraftPostgresRepository
    implements FlowDraftRepository {

    @Override
    public List<FlowDraft> findAll(
        DSLContext dsl,
        String companyId
    ) {
        return dsl.selectFrom(FLOW_DRAFTS)
            .where(FLOW_DRAFTS.COMPANY_ID.eq(companyId))
            .and(FLOW_DRAFTS.DELETED.eq(false))
            .orderBy(
                FLOW_DRAFTS.UPDATED_AT.desc(),
                FLOW_DRAFTS.ID.asc()
            )
            .fetch(FlowDraftEntry::fromRecord)
            .stream()
            .map(FlowDraftEntry::toDomain)
            .toList();
    }

    @Override
    public Optional<FlowDraft> findById(
        DSLContext dsl,
        String companyId,
        String rowId
    ) {
        FlowDraftEntry entry = dsl.selectFrom(FLOW_DRAFTS)
            .where(FLOW_DRAFTS.COMPANY_ID.eq(companyId))
            .and(FLOW_DRAFTS.ID.eq(rowId))
            .fetchOne(FlowDraftEntry::fromRecord);
        return entry == null
            ? Optional.empty()
            : Optional.of(entry.toDomain());
    }

    @Override
    public Optional<FlowDraft> findByFlowKey(
        DSLContext dsl,
        String companyId,
        String flowKey
    ) {
        FlowDraftEntry entry = dsl.selectFrom(FLOW_DRAFTS)
            .where(FLOW_DRAFTS.COMPANY_ID.eq(companyId))
            .and(FLOW_DRAFTS.FLOW_KEY.eq(flowKey))
            .and(FLOW_DRAFTS.DELETED.eq(false))
            .fetchOne(FlowDraftEntry::fromRecord);
        return entry == null
            ? Optional.empty()
            : Optional.of(entry.toDomain());
    }

    @Override
    public Optional<FlowDraft> lockByFlowKey(
        DSLContext dsl,
        String companyId,
        String flowKey
    ) {
        FlowDraftEntry entry = dsl.selectFrom(FLOW_DRAFTS)
            .where(FLOW_DRAFTS.COMPANY_ID.eq(companyId))
            .and(FLOW_DRAFTS.FLOW_KEY.eq(flowKey))
            .and(FLOW_DRAFTS.DELETED.eq(false))
            .forUpdate()
            .fetchOne(FlowDraftEntry::fromRecord);
        return entry == null
            ? Optional.empty()
            : Optional.of(entry.toDomain());
    }

    @Override
    public Optional<FlowDraft> lockById(
        DSLContext dsl,
        String companyId,
        String rowId
    ) {
        FlowDraftEntry entry = dsl.selectFrom(FLOW_DRAFTS)
            .where(FLOW_DRAFTS.COMPANY_ID.eq(companyId))
            .and(FLOW_DRAFTS.ID.eq(rowId))
            .and(FLOW_DRAFTS.DELETED.eq(false))
            .forUpdate()
            .fetchOne(FlowDraftEntry::fromRecord);
        return entry == null
            ? Optional.empty()
            : Optional.of(entry.toDomain());
    }

    @Override
    public void save(DSLContext dsl, FlowDraft draft) {
        FlowDraftEntry stored = dsl.selectFrom(FLOW_DRAFTS)
            .where(FLOW_DRAFTS.COMPANY_ID.eq(draft.companyId()))
            .and(FLOW_DRAFTS.ID.eq(draft.id()))
            .forUpdate()
            .fetchOne(FlowDraftEntry::fromRecord);
        FlowDraftEntry entry = FlowDraftEntry.fromDomain(draft);
        if (stored == null) {
            insert(dsl, draft, entry);
            return;
        }

        FlowDraft storedDraft = stored.toDomain();
        long storedVersion = stored.lockVersion == null
            ? 0
            : stored.lockVersion;
        if (!draft.hasLockVersion(storedVersion + 1)) {
            throw conflict(draft, storedVersion);
        }
        requireAllowedChange(storedDraft, draft);
        int updated = dsl.update(FLOW_DRAFTS)
            .set(entry.buildUpdateMap())
            .where(FLOW_DRAFTS.COMPANY_ID.eq(draft.companyId()))
            .and(FLOW_DRAFTS.ID.eq(draft.id()))
            .and(FLOW_DRAFTS.LOCK_VERSION.eq(storedVersion))
            .and(FLOW_DRAFTS.DELETED.eq(false))
            .execute();
        if (updated != 1) {
            throw conflict(draft, storedVersion);
        }
    }

    private static void insert(
        DSLContext dsl,
        FlowDraft draft,
        FlowDraftEntry entry
    ) {
        if (!draft.hasLockVersion(0) || draft.isDeleted()) {
            throw conflict(draft, -1);
        }
        try {
            dsl.insertInto(FLOW_DRAFTS)
                .set(entry.buildInsertMap())
                .execute();
        } catch (DataAccessException exception) {
            throw new WorkflowException(
                "FlowDraft key conflict for "
                    + draft.companyId() + ":" + draft.flowKey(),
                exception
            );
        }
    }

    private static WorkflowException conflict(
        FlowDraft draft,
        long storedVersion
    ) {
        return new WorkflowException(
            "FlowDraft lock conflict for " + draft.id()
                + ": stored " + storedVersion
                + ", attempted " + draft.lockVersion()
        );
    }

    private static void requireAllowedChange(
        FlowDraft stored,
        FlowDraft attempted
    ) {
        boolean identityAndCreationAuditMatch =
            stored.identifiedBy(attempted.identifier())
                && stored.companyId().equals(attempted.companyId())
                && stored.flowKey().equals(attempted.flowKey())
                && stored.creator().equals(attempted.creator())
                && stored.createdAt() == attempted.createdAt();
        if (!identityAndCreationAuditMatch
            || stored.isDeleted()) {
            throw new WorkflowException(
                "FlowDraft update violates lifecycle invariants: "
                    + attempted.id()
            );
        }
        if (attempted.isDeleted()
            && !stored.raw().equals(attempted.raw())) {
            throw new WorkflowException(
                "Deleting a FlowDraft must not change its raw definition: "
                    + attempted.id()
            );
        }
    }
}
