package org.cses.flow.core.utils;

import org.cses.flow.core.domains.ActorRef;
import org.paas.exception.DataException;
import org.paas.session.Session;
import org.paas.session.User;

public final class SessionUtil {

    private SessionUtil() {
    }

    public static String company(Session<? extends User> session) {
        Session<? extends User> requiredSession = RequiredUtil.required(
            session,
            "session is required"
        );
        return RequiredUtil.required(
            requiredSession.getCompanyId(),
            "session.companyId is required"
        ).trim();
    }

    public static ActorRef user(Session<? extends User> session) {
        String companyId = company(session);
        User sessionUser = RequiredUtil.required(
            session.getUser(),
            "current user is required"
        );
        String userId = RequiredUtil.required(
            sessionUser.getId(),
            "current user id is required"
        );
        if (!companyId.equals(sessionUser.getCompanyId())) {
            throw new DataException("current user does not belong to company");
        }
        String actorName = sessionUser.getName();
        if (actorName == null || actorName.isBlank()) {
            actorName = session.getUserName();
        }
        return ActorRef.create(userId, actorName);
    }
}
