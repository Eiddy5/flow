package org.cses.flow.controller.session;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.bind.ArgumentBinder;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.AnnotatedRequestArgumentBinder;
import jakarta.inject.Singleton;
import org.paas.common.client.Device;
import org.paas.session.OrganizeType;
import org.paas.session.Session;
import org.paas.session.User;
import org.paas.session.bind.SessionArgumentBinder;
import org.paas.session.bind.UserSession;

import java.util.Optional;

/**
 * Temporary default identity for the standalone Flow management surface.
 *
 * <p>This adapter keeps the management controller on the normal
 * {@link UserSession} contract while the host authentication integration is
 * not wired yet. It must be disabled before exposing the server to real
 * users.</p>
 */
@Singleton
@Requires(
    property = "flow.management.admin-session.enabled",
    value = "true",
    defaultValue = "true"
)
//@Replaces(SessionArgumentBinder.class)
public final class AdminSessionArgumentBinder
    implements AnnotatedRequestArgumentBinder<
        UserSession,
        Session<User>
    > {

    public static final String ENABLED_PROPERTY =
        "flow.management.admin-session.enabled";

    private static final String ADMIN = "admin";
    private static final String DEVICE_ID = "flow-management-admin";

    @Override
    public Class<UserSession> getAnnotationType() {
        return UserSession.class;
    }

    @Override
    public ArgumentBinder.BindingResult<Session<User>> bind(
        ArgumentConversionContext<Session<User>> context,
        HttpRequest<?> request
    ) {
        Session<User> session = new Session<>();
        session.setId(ADMIN);
        session.setCompanyId(ADMIN);
        session.setDevice(Device.WebBrowser);
        session.setDeviceId(DEVICE_ID);

        User user = new User();
        user.setId(ADMIN);
        user.setName(ADMIN);
        user.setUserName(ADMIN);
        user.setCompanyId(ADMIN);
        user.setOrgRole(OrganizeType.Admin);
        session.setUser(user);

        return () -> Optional.of(session);
    }
}
