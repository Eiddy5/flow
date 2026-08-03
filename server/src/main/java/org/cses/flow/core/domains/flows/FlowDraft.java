package org.cses.flow.core.domains.flows;

import org.cses.flow.core.exceptions.WorkflowException;
import org.paas.common.util.StringUtil;

import java.util.Objects;
import java.util.Optional;

/**
 * The unique editable draft aggregate for one logical Flow.
 */
public final class FlowDraft {

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
        raw = requireRaw(revisedRaw);
        updater = Objects.requireNonNull(
            revisedBy,
            "FlowDraft updater"
        );
        updatedAt = Objects.requireNonNull(
            revisedAt,
            "FlowDraft updatedAt"
        );
        lockVersion++;
    }

    public void delete(ActorRef deletedBy, long deletionTime) {
        ensureEditable();
        deleter = Objects.requireNonNull(
            deletedBy,
            "FlowDraft deleter"
        );
        deletedAt = deletionTime;
        updater = deletedBy;
        updatedAt = deletionTime;
        deleted = true;
        lockVersion++;
    }

    public void requireLockVersion(long expectedLockVersion) {
        if (lockVersion != expectedLockVersion) {
            throw new WorkflowException(
                "FlowDraft lock conflict for " + id
                    + ": expected " + expectedLockVersion
                    + " but was " + lockVersion
            );
        }
    }

    public String id() {
        return id;
    }

    public String companyId() {
        return companyId;
    }

    public String raw() {
        return raw;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public ActorRef creator() {
        return creator;
    }

    public ActorRef updater() {
        return updater;
    }

    public Optional<ActorRef> deleter() {
        return Optional.ofNullable(deleter);
    }

    public long createdAt() {
        return createdAt;
    }

    public long updatedAt() {
        return updatedAt;
    }

    public Optional<Long> deletedAt() {
        return Optional.ofNullable(deletedAt);
    }

    public long lockVersion() {
        return lockVersion;
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
}
