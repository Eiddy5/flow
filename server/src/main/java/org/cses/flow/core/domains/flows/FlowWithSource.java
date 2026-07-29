package org.cses.flow.core.domains.flows;

import org.cses.flow.core.exceptions.shared.WorkflowException;
import org.paas.common.util.StringUtil;

import java.util.Objects;
import java.util.Optional;

/**
 * The unique editable source aggregate for one logical Flow.
 */
public final class FlowWithSource {

    private final String id;
    private final String companyId;
    private final ActorRef creator;
    private final long createdAt;
    private String raw;
    private ActorRef updater;
    private ActorRef deleter;
    private long updatedAt;
    private Long deletedAt;
    private long lockVersion;

    private FlowWithSource(
        String id,
        String companyId,
        String raw,
        ActorRef creator,
        ActorRef updater,
        ActorRef deleter,
        long createdAt,
        long updatedAt,
        Long deletedAt,
        long lockVersion
    ) {
        this.id = requireText(id, "FlowWithSource id");
        this.companyId = requireText(companyId, "Company id");
        this.raw = requireRaw(raw);
        this.creator = Objects.requireNonNull(
            creator,
            "FlowWithSource creator"
        );
        this.updater = Objects.requireNonNull(
            updater,
            "FlowWithSource updater"
        );
        this.deleter = deleter;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.deletedAt = deletedAt;
        if ((deleter == null) != (deletedAt == null)) {
            throw new IllegalArgumentException(
                "FlowWithSource deleter and deletedAt must both be empty "
                    + "or both be present"
            );
        }
        if (lockVersion < 0) {
            throw new IllegalArgumentException(
                "FlowWithSource lockVersion must not be negative"
            );
        }
        this.lockVersion = lockVersion;
    }

    public static FlowWithSource create(
        String companyId,
        String raw,
        ActorRef creator,
        long createdAt
    ) {
        return new FlowWithSource(
            StringUtil.newId(),
            companyId,
            raw,
            creator,
            creator,
            null,
            createdAt,
            createdAt,
            null,
            0
        );
    }

    public static FlowWithSource rehydrate(
        String id,
        String companyId,
        String raw,
        ActorRef creator,
        ActorRef updater,
        ActorRef deleter,
        long createdAt,
        long updatedAt,
        Long deletedAt,
        long lockVersion
    ) {
        return new FlowWithSource(
            id,
            companyId,
            raw,
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
            "FlowWithSource updater"
        );
        updatedAt = Objects.requireNonNull(
            revisedAt,
            "FlowWithSource updatedAt"
        );
        lockVersion++;
    }

    public void discard(ActorRef discardedBy, long discardedAt) {
        ensureEditable();
        deleter = Objects.requireNonNull(
            discardedBy,
            "FlowWithSource deleter"
        );
        deletedAt = discardedAt;
        updater = discardedBy;
        updatedAt = discardedAt;
        lockVersion++;
    }

    public void requireLockVersion(long expectedLockVersion) {
        if (lockVersion != expectedLockVersion) {
            throw new WorkflowException(
                "FlowWithSource lock conflict for " + id
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

    public boolean isDiscarded() {
        return deletedAt != null;
    }

    public FlowWithSource copy() {
        return rehydrate(
            id,
            companyId,
            raw,
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
        if (!(value instanceof FlowWithSource other)) {
            return false;
        }
        return lockVersion == other.lockVersion
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
        if (isDiscarded()) {
            throw new WorkflowException(
                "Discarded FlowWithSource cannot be changed: " + id
            );
        }
    }

    private static String requireRaw(String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                "FlowWithSource raw must not be null"
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
