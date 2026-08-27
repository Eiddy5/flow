package org.cses.flow.core.domains;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.utils.RequiredUtil;
import org.cses.flow.core.utils.SessionUtil;
import org.cses.flow.core.utils.TimeUtil;
import org.paas.common.util.StringUtil;
import org.paas.session.Session;
import org.paas.session.User;

/**
 * Base for tenant-scoped domain objects with a stable string entity id.
 */
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class BaseDomain implements Identified {

    String id;
    String companyId;
    ActorRef creator;
    Long createdAt;


    protected BaseDomain(
            String id,
            Session<? extends User> session
    ) {
        this(
                id,
                SessionUtil.company(session),
                SessionUtil.user(session),
                TimeUtil.now()
        );
    }

    protected BaseDomain(
            String id,
            String companyId,
            ActorRef creator,
            long createdAt
    ) {
        this.id = RequiredUtil.required(
                id,
                "Domain id must not be blank"
        ).trim();
        this.companyId = RequiredUtil.required(
                companyId,
                "Domain company id"
        ).trim();
        this.creator = RequiredUtil.required(creator, "Domain creator");
        if (createdAt < 0) {
            throw new IllegalArgumentException(
                    "Domain createdAt must not be negative"
            );
        }
        this.createdAt = createdAt;
    }

    protected void initializeIdentity(Session<? extends User> session) {
        if (id != null) {
            throw new IllegalStateException("Domain identity is initialized");
        }
        id = StringUtil.newId();
        companyId = SessionUtil.company(session);
        creator = SessionUtil.user(session);
        createdAt = TimeUtil.now();
    }

    @Override
    public final String id() {
        return id;
    }

    public final String companyId() {
        return companyId;
    }

    public final ActorRef creator() {
        return creator;
    }

    public final long createdAt() {
        return createdAt;
    }

    protected final void requireSessionCompany(
            Session<? extends User> session
    ) {
        String sessionCompanyId = SessionUtil.company(session);
        if (!companyId.equals(sessionCompanyId)) {
            throw new WorkflowException(
                    "Session company cannot change " + id()
            );
        }
    }
}
