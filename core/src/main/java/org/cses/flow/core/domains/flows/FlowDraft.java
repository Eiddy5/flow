package org.cses.flow.core.domains.flows;

import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.Deletable;
import org.cses.flow.core.domains.Lockable;
import org.cses.flow.core.exceptions.WorkflowException;
import org.paas.common.util.StringUtil;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Objects;
import java.util.Optional;

/**
 * The unique editable draft aggregate for one logical Flow.
 */
public final class FlowDraft
    implements Deletable<FlowDraft>, Lockable<FlowDraft> {

    private final String id;
    private final String companyId;
    private final ActorRef creator;
    private final long createdAt;
    private String raw;
    private boolean deleted;
    private ActorRef updater;
    private ActorRef deleter;
    private long updatedAt;
    private Long deletedAt;
    private long lockVersion;

    private FlowDraft(
        String id,
        String companyId,
        String raw,
        boolean deleted,
        ActorRef creator,
        ActorRef updater,
        ActorRef deleter,
        long createdAt,
        long updatedAt,
        Long deletedAt,
        long lockVersion
    ) {
        this.id = requireText(id, "FlowDraft id");
        this.companyId = requireText(companyId, "Company id");
        this.raw = requireRaw(raw);
        this.deleted = deleted;
        this.creator = Objects.requireNonNull(
            creator,
            "FlowDraft creator"
        );
        this.updater = Objects.requireNonNull(
            updater,
            "FlowDraft updater"
        );
        this.deleter = deleter;
        if (createdAt < 0 || updatedAt < createdAt) {
            throw new IllegalArgumentException(
                "FlowDraft timestamps are invalid"
            );
        }
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.deletedAt = deletedAt;
        if ((deleter == null) != (deletedAt == null)) {
            throw new IllegalArgumentException(
                "FlowDraft deleter and deletedAt must both be empty "
                    + "or both be present"
            );
        }
        if (!deleted && deleter != null) {
            throw new IllegalArgumentException(
                "Undeleted FlowDraft must not have deletion audit"
            );
        }
        if (deleted && deleter == null) {
            throw new IllegalArgumentException(
                "Deleted FlowDraft requires deletion audit"
            );
        }
        if (lockVersion < 0) {
            throw new IllegalArgumentException(
                "FlowDraft lockVersion must not be negative"
            );
        }
        this.lockVersion = lockVersion;
    }

    public static FlowDraft create(
        String companyId,
        String raw,
        ActorRef creator,
        long createdAt
    ) {
        return new FlowDraft(
            StringUtil.newId(),
            companyId,
            raw,
            false,
            creator,
            creator,
            null,
            createdAt,
            createdAt,
            null,
            0
        );
    }

    public static FlowDraft rehydrate(
        String id,
        String companyId,
        String raw,
        boolean deleted,
        ActorRef creator,
        ActorRef updater,
        ActorRef deleter,
        long createdAt,
        long updatedAt,
        Long deletedAt,
        long lockVersion
    ) {
        return new FlowDraft(
            id,
            companyId,
            raw,
            deleted,
            creator,
            updater,
            deleter,
            createdAt,
            updatedAt,
            deletedAt,
            lockVersion
        );
    }

    public void revise(
        String revisedRaw,
        ActorRef revisedBy,
        long revisedAt
    ) {
        ensureEditable();
        String checkedRaw = requireRaw(revisedRaw);
        updateAudit(revisedBy, revisedAt);
        raw = checkedRaw;
    }

    public void revise(
        String revisedRaw,
        Session<? extends User> session,
        long revisedAt
    ) {
        requireSessionCompany(session);
        revise(revisedRaw, ActorRef.from(session), revisedAt);
    }

    @Override
    public FlowDraft delete(
        Session<? extends User> session,
        long deletionTime
    ) {
        requireSessionCompany(session);
        return delete(ActorRef.from(session), deletionTime);
    }

    public FlowDraft delete(ActorRef deletedBy, long deletionTime) {
        ensureEditable();
        ActorRef actor = Objects.requireNonNull(
            deletedBy,
            "FlowDraft deleter"
        );
        updateAudit(actor, deletionTime);
        deleter = actor;
        deletedAt = deletionTime;
        deleted = true;
        return this;
    }

    @Override
    public FlowDraft updateAudit(
        Session<? extends User> session,
        long updateTime
    ) {
        requireSessionCompany(session);
        return updateAudit(ActorRef.from(session), updateTime);
    }

    public FlowDraft updateAudit(ActorRef updatedBy, long updateTime) {
        ensureEditable();
        if (updateTime < updatedAt) {
            throw new IllegalArgumentException(
                "FlowDraft update time must not move backwards"
            );
        }
        updater = Objects.requireNonNull(updatedBy, "FlowDraft updater");
        updatedAt = updateTime;
        lock();
        return this;
    }

    public String id() {
        return id;
    }

    @Override
    public String identifier() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public String raw() {
        return raw;
    }

    @Override
    public boolean isDeleted() {
        return deleted;
    }

    @Override
    public ActorRef creator() {
        return creator;
    }

    @Override
    public ActorRef updater() {
        return updater;
    }

    @Override
    public Optional<ActorRef> deleter() {
        return Optional.ofNullable(deleter);
    }

    @Override
    public long createdAt() {
        return createdAt;
    }

    @Override
    public long updatedAt() {
        return updatedAt;
    }

    @Override
    public Optional<Long> deletedAt() {
        return Optional.ofNullable(deletedAt);
    }

    @Override
    public long lockVersion() {
        return lockVersion;
    }

    @Override
    public FlowDraft lock() {
        ensureEditable();
        lockVersion++;
        return this;
    }

    public FlowDraft copy() {
        return rehydrate(
            id,
            companyId,
            raw,
            deleted,
            creator,
            updater,
            deleter,
            createdAt,
            updatedAt,
            deletedAt,
            lockVersion
        );
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (!(value instanceof FlowDraft other)) {
            return false;
        }
        return lockVersion == other.lockVersion
            && deleted == other.deleted
            && Objects.equals(id, other.id)
            && Objects.equals(companyId, other.companyId)
            && Objects.equals(raw, other.raw)
            && Objects.equals(creator, other.creator)
            && Objects.equals(updater, other.updater)
            && Objects.equals(deleter, other.deleter)
            && Objects.equals(createdAt, other.createdAt)
            && Objects.equals(updatedAt, other.updatedAt)
            && Objects.equals(deletedAt, other.deletedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            id,
            companyId,
            raw,
            deleted,
            creator,
            updater,
            deleter,
            createdAt,
            updatedAt,
            deletedAt,
            lockVersion
        );
    }

    private void ensureEditable() {
        if (deleted) {
            throw new WorkflowException(
                "Deleted FlowDraft cannot be changed: " + id
            );
        }
    }

    private static String requireRaw(String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                "FlowDraft raw must not be null"
            );
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private void requireSessionCompany(Session<? extends User> session) {
        Objects.requireNonNull(session, "Session");
        String sessionCompanyId = requireText(
            session.getCompanyId(),
            "Session company id"
        );
        if (!companyId.equals(sessionCompanyId)) {
            throw new WorkflowException(
                "Session company cannot change FlowDraft " + id
            );
        }
    }
}
