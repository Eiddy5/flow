package org.cses.flow.core.utils;

import org.cses.flow.core.domains.ActorRef;
import org.paas.exception.DataException;
import org.paas.session.Session;
import org.paas.session.User;

public class SessionUtil {

    public static <S extends Session<U>,U extends User> String company(S session ) {
        AssertUtil.assertNotNull(session, "session is required");
        AssertUtil.assertNotBlank(session.getCompanyId().trim(), "session.companyId is required");
        return session.getCompanyId();
    }

    public static  <S extends Session<U>,U extends User> ActorRef user(S session) {
        String companyId = company(session);
        org.paas.session.User sessionUser = session.getUser();
        if (sessionUser == null) {
            throw new DataException("current user is required");
        }
        if (sessionUser.getId() == null || sessionUser.getId().isBlank()) {
            throw new DataException("current user id is required");
        }
        if (!companyId.equals(sessionUser.getCompanyId())) {
            throw new DataException("current user does not belong to company");
        }
        return ActorRef.from(session);
    }
}
