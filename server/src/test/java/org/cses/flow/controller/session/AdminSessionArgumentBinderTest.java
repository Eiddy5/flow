package org.cses.flow.controller.session;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import org.junit.jupiter.api.Test;
import org.paas.session.Device;
import org.paas.session.OrganizeType;
import org.paas.session.Session;
import org.paas.session.User;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminSessionArgumentBinderTest {

    @Test
    void bindsTheGlobalAdminIdentityForEveryManagementRequest() {
        AdminSessionArgumentBinder binder = new AdminSessionArgumentBinder();

        Session<User> session = binder.bind(
            null,
            HttpRequest.GET("/flow/index.html")
        ).getValue().orElseThrow();

        assertEquals("admin", session.getId());
        assertEquals("admin", session.getCompanyId());
        assertEquals("admin", session.getUserId());
        assertEquals("admin", session.getUserName());
        assertEquals("admin", session.getName());
        assertSame(Device.WebBrowser, session.getDevice());
        assertEquals("flow-management-admin", session.getDeviceId());
        assertSame(OrganizeType.Admin, session.getUser().getOrgRole());
        assertTrue(session.getUser().isRootOrAdmin());
    }

    @Test
    void isEnabledByDefaultAndCanBeDisabledByConfiguration() {
        Requires requires = AdminSessionArgumentBinder.class.getAnnotation(
            Requires.class
        );

        assertEquals(
            AdminSessionArgumentBinder.ENABLED_PROPERTY,
            requires.property()
        );
        assertEquals("true", requires.value());
        assertEquals("true", requires.defaultValue());
    }
}
