package org.cses.flow.core.services.shared;

import org.paas.session.Session;
import org.paas.session.User;

/**
 * Shared validation for the tenant identity carried by a PAAS Session.
 */
public final class SessionValidation {

    private SessionValidation() {
    }

    public static String requireCompanyId(
        Session<? extends User> session
    ) {
        if (session == null) {
            throw new IllegalArgumentException("Session must not be null");
        }
        String companyId = session.getCompanyId();
        if (companyId == null || companyId.isBlank()) {
            throw new IllegalArgumentException(
                "Session company id must not be blank"
            );
        }
        return companyId.trim();
    }
}
