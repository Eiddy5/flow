package org.cses.flow.core.domains;

import org.paas.session.Session;
import org.paas.session.User;

import java.util.Optional;

/**
 * Complete, irreversible soft-deletion capability for a domain object.
 */
public interface Deletable<T extends Deletable<T>> extends Auditable<T> {

    T delete(Session<? extends User> session, long deletedAt);

    boolean isDeleted();

    Optional<ActorRef> deleter();

    Optional<Long> deletedAt();
}
