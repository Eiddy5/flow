package org.cses.flow.controller.session;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionControllerTest {

    @Test
    void ownsTheManagementSessionRoute() throws NoSuchMethodException {
        Controller controller = SessionController.class.getAnnotation(
            Controller.class
        );
        Method session = SessionController.class.getDeclaredMethod(
            "session",
            Session.class
        );

        assertEquals("/api", controller.value());
        assertEquals("/session", session.getAnnotation(Get.class).value());
    }
}
