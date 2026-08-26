package org.cses.flow.core.domains;

import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.utils.RequiredUtil;
import org.cses.flow.core.utils.SessionUtil;
import org.paas.session.RecordState;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Optional;

/**
 * Tenant-scoped domain base that owns current Audit Status and audit facts.
 */
public abstract class Audited extends BaseDomain {

    RecordState status;
    ActorRef updater;
    long updatedAt;
    ActorRef deleter;
    Long deletedAt;

    protected Audited() {
        super();
    }

    protected Audited(
        String id,
        Session<? extends User> session
    ) {
        super(id, session);
        status = RecordState.Open;
        updater = creator();
        updatedAt = createdAt();
    }

    protected Audited(
        String id,
        String companyId,
        ActorRef creator,
        long createdAt,
        RecordState status,
        ActorRef updater,
        long updatedAt,
        ActorRef deleter,
        Long deletedAt
    ) {
        super(id, companyId, creator, createdAt);
        this.status = RequiredUtil.required(status, "Audit status");
        this.updater = RequiredUtil.required(updater, "Audit updater");
        this.updatedAt = updatedAt;
        this.deleter = deleter;
        this.deletedAt = deletedAt;
        validateAuditState();
    }

    protected void initializeAudit(Session<? extends User> session) {
        initializeIdentity(session);
        status = RecordState.Open;
        updater = creator;
        updatedAt = createdAt;
        deleter = null;
        deletedAt = null;
    }

    public final RecordState status() {
        return status;
    }

    public final ActorRef updater() {
        return updater;
    }

    public final long updatedAt() {
        return updatedAt;
    }

    public final Optional<ActorRef> deleter() {
        return Optional.ofNullable(deleter);
    }

    public final Optional<Long> deletedAt() {
        return Optional.ofNullable(deletedAt);
    }

    public final boolean deleted() {
        return RecordState.Delete.equals(status);
    }

    /**
     * Compatibility transition for every supported Audit Status.
     */
    public final void withState(
        RecordState nextStatus,
        Session<? extends User> session,
        long changedAt
    ) {
        requireSessionCompany(session);
        RecordState checkedStatus = RequiredUtil.required(
            nextStatus,
            "Audit status"
        );
        ActorRef actor = RequiredUtil.required(
            SessionUtil.user(session),
            "Audit state operator"
        );
        if (deleted()) {
            throw deletedChange();
        }
        if (checkedStatus.equals(status)) {
            return;
        }
        if (RecordState.Delete.equals(checkedStatus)) {
            delete(actor, changedAt);
            return;
        }
        requireUpdateTime(changedAt);
        status = checkedStatus;
        updater = actor;
        updatedAt = changedAt;
        onAuditChanged();
    }

    public final void delete(
        Session<? extends User> session,
        long deletedAt
    ) {
        requireSessionCompany(session);
        delete(SessionUtil.user(session), deletedAt);
    }

    private void delete(ActorRef deletedBy, long deletedAt) {
        if (deleted()) {
            throw new WorkflowException(
                "Domain object is already deleted: " + id()
            );
        }
        ActorRef actor = RequiredUtil.required(
            deletedBy,
            "Audit deleter"
        );
        requireUpdateTime(deletedAt);
        this.status = RecordState.Delete;
        this.updater = actor;
        this.updatedAt = deletedAt;
        this.deleter = actor;
        this.deletedAt = this.updatedAt;
        onAuditChanged();
    }

    protected void update(
        Session<? extends User> session,
        long updateTime
    ) {
        requireSessionCompany(session);
        if (deleted()) {
            throw deletedChange();
        }
        requireUpdateTime(updateTime);
        updater = RequiredUtil.required(
            SessionUtil.user(session),
            "Audited.updater is required"
        );
        updatedAt = updateTime;
        onAuditChanged();
    }

    /**
     * Lets a concrete aggregate advance its concurrency fact exactly once.
     */
    protected void onAuditChanged() {
    }

    private void validateAuditState() {
        if (updatedAt < createdAt()) {
            throw new IllegalArgumentException(
                "Audit updatedAt must not precede createdAt"
            );
        }
        boolean hasDeleter = deleter != null;
        boolean hasDeletedAt = deletedAt != null;
        if (hasDeleter != hasDeletedAt) {
            throw new IllegalArgumentException(
                "Audit deleter and deletedAt must both be empty or present"
            );
        }
        if (deleted()) {
            if (!hasDeleter) {
                throw new IllegalArgumentException(
                    "Delete status requires deletion audit"
                );
            }
            if (!updater.equals(deleter)
                || updatedAt != deletedAt.longValue()) {
                throw new IllegalArgumentException(
                    "Delete audit must also be the last update audit"
                );
            }
        } else if (hasDeleter) {
            throw new IllegalArgumentException(
                "Non-delete status must not have deletion audit"
            );
        }
    }

    private void requireUpdateTime(long updateTime) {
        if (updateTime < updatedAt) {
            throw new IllegalArgumentException(
                "Audit update time must not move backwards"
            );
        }
    }

    private WorkflowException deletedChange() {
        return new WorkflowException(
            "Deleted domain object cannot be changed: " + id()
        );
    }
}
