package org.cses.flow.infrastructure.session;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.bind.ArgumentBinder;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.AnnotatedRequestArgumentBinder;
import jakarta.inject.Singleton;
import org.paas.session.Session;
import org.paas.session.User;
import org.paas.session.bind.SessionArgumentBinder;
import org.paas.session.bind.UserSession;

import java.util.Optional;

/**
 * Test-only HTTP adapter for the PAAS Session contract.
 *
 * <p>The persisted PAAS session binder requires Redis. Flow Core currently
 * receives Session explicitly, so server-flow tests must not require the
 * unrelated Redis session cache.</p>
 */
@Singleton
@Replaces(SessionArgumentBinder.class)
public final class TestSessionArgumentBinder
    implements AnnotatedRequestArgumentBinder<
        UserSession,
        Session<User>
    > {

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
        session.init(request);
        User user = new User();
        user.setId(session.getId());
        user.setCompanyId(session.companyId);
        session.setUser(user);
        return () -> Optional.of(session);
    }
}
