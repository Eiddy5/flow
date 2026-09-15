package org.cses.flow.controller.session;

import lombok.Getter;
import lombok.Setter;
import org.paas.json.SerializableObject;
import org.paas.session.Session;
import org.paas.session.SessionUser;

/**
 * HTTP protocol models for the authenticated management session.
 */
public class SessionModels {

    private SessionModels() {
    }

    @Getter
    @Setter
    public static class SessionView extends SerializableObject {

        private String companyId;
        private String userId;
        private String userName;

        private SessionView(
                String companyId,
                String userId,
                String userName
        ) {
            this.companyId = companyId;
            this.userId = userId;
            this.userName = userName;
        }

        /**
         * 将可信会话投影为 HTTP 用户信息，不暴露认证凭证或成员资料。
         * @param <U> 继承 SessionUser 的具体用户类型
         * @param session 非空且已验证的会话
         * @return 当前公司、用户 ID 与显示名称组成的响应
         */
        public static <U extends SessionUser> SessionView from(Session<U> session) {
            return new SessionView(
                    session.getCompanyId(),
                    session.getUserId(),
                    session.getName()
            );
        }

        public String getCompanyId() {
            return companyId;
        }

        public String getUserId() {
            return userId;
        }

        public String getUserName() {
            return userName;
        }
    }
}
