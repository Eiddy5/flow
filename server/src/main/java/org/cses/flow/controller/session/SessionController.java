package org.cses.flow.controller.session;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import org.cses.flow.controller.session.SessionModels.SessionView;
import org.paas.session.Session;
import org.paas.session.User;
import org.paas.session.bind.UserSession;

/**
 * User-facing HTTP adapter for the authenticated management session.
 */
@Controller("/api")
public class SessionController {

    @Get("/session")
    public SessionView session(
            @UserSession Session<User> session
    ) {
        return SessionView.from(session);
    }
}
