package org.cses.flow.infrastructure.session;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.bind.ArgumentBinder;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.AnnotatedRequestArgumentBinder;
import jakarta.inject.Singleton;
import org.paas.session.Device;
import org.paas.session.Session;
import org.paas.session.User;
import org.paas.session.bind.SessionArgumentBinder;
import org.paas.session.bind.UserSession;

import java.util.Optional;

/**
 * Supplies one server-controlled tenant and user to the local Flow Demo.
 *
 * <p>The identity is read from server configuration instead of request
 * headers, so callers cannot switch tenant by crafting browser headers.</p>
 */
@Singleton
@Replaces(SessionArgumentBinder.class)
@Requires(
    property = "flow.demo.session-binder.enabled",
    value = "true",
    defaultValue = "false"
)
public final class DemoSessionArgumentBinder
    implements AnnotatedRequestArgumentBinder<
        UserSession,
        Session<User>
    > {

    private final String companyId;
    private final String userId;
    private final String userName;

    public DemoSessionArgumentBinder(
        @Value("${flow.demo.company-id}") String companyId,
        @Value("${flow.demo.user-id}") String userId,
        @Value("${flow.demo.user-name}") String userName
    ) {
        this.companyId = requireText(companyId, "Demo company id");
        this.userId = requireText(userId, "Demo user id");
        this.userName = requireText(userName, "Demo user name");
    }

    @Override
    public Class<UserSession> getAnnotationType() {
        return UserSession.class;
    }

    @Override
    public ArgumentBinder.BindingResult<Session<User>> bind(
        ArgumentConversionContext<Session<User>> context,
        HttpRequest<?> request
    ) {
        User user = new User();
        user.setId(userId);
        user.setUserName(userName);
        user.setName(userName);
        user.setCompanyId(companyId);

        Session<User> session = new Session<>();
        session.setId(userId);
        session.setCompanyId(companyId);
        session.setDevice(Device.WebBrowser);
        session.setDeviceId("flow-demo-browser");
        session.setUser(user);
        return () -> Optional.of(session);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                field + " must not be blank"
            );
        }
        return value.trim();
    }
}
