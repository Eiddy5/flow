package org.cses.flow.core.services.shared;

import org.paas.session.Session;
import org.paas.session.SessionUser;

/**
 * Shared validation for the tenant identity carried by a PAAS Session.
 */
public final class SessionValidation {

    private SessionValidation() {
    }

    /**
     * 读取会话当前公司的非空 ID，供租户查询使用。
     * @param session 非空的可信会话，不要求用户携带旧公司字段
     * @return 去除首尾空白的公司 ID
     * @throws IllegalArgumentException 会话或公司 ID 为空时抛出
     */
    public static String requireCompanyId(
        Session<? extends SessionUser> session
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
