package org.cses.flow.core.domains;

import org.paas.session.Session;
import org.paas.session.User;

/**
 * Domain capability that owns creation and last-update audit facts.
 */
public interface Auditable<T extends Auditable<T>> extends Identified {

    ActorRef creator();

    long createdAt();

    ActorRef updater();

    long updatedAt();

    T updateAudit(Session<? extends User> session, long updatedAt);
}
