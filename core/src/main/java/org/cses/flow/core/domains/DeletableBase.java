package org.cses.flow.core.domains;

import org.cses.flow.core.utils.SessionUtil;
import org.paas.session.Session;
import org.paas.session.User;

public abstract class DeletableBase<ID extends Identity> extends BaseDomain<ID> {

    private ActorRef deleter;
    private Long deletedAt;

    public DeletableBase(ID id, Session<? extends User> session) {
        super(id, session);
    }


    public ActorRef deleter() {
        return deleter;
    }

    public Long deletedAt() {
        return deletedAt;
    }

    public boolean delete(Session<? extends User> session) {
        if (deleted()) return true;
        this.deleter = SessionUtil.user(session);
        this.deletedAt = System.currentTimeMillis();
        return true;
    }

    public boolean deleted() {
        return this.deleter != null && deletedAt != null;
    }


}
