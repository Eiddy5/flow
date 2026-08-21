package org.cses.flow.core.domains;

import org.cses.flow.core.utils.SessionUtil;
import org.paas.session.Session;
import org.paas.session.User;


public abstract class BaseDomain<ID extends Identity> {

    private final ID id;
    private final String companyId;
    private final ActorRef creator;
    private final Long createdAt;


    protected BaseDomain(ID id, Session<? extends User> session) {
        id.valid();
        this.id = id;
        this.companyId = SessionUtil.company(session);
        this.creator = SessionUtil.user(session);
        this.createdAt = System.currentTimeMillis();
    }


    public ID id(){
        return id;
    }


    public String companyId() {
        return companyId;
    }

    public ActorRef creator() {
        return creator;
    }

    public Long createdAt() {
        return createdAt;
    }


}
