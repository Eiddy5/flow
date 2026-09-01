package org.cses.flow.controller.session;

import lombok.Getter;
import lombok.Setter;
import org.paas.json.SerializableObject;
import org.paas.session.Session;
import org.paas.session.User;

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

        public static SessionView from(Session<User> session) {
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
