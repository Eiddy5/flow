package org.cses.flow.controller.session;

import org.paas.response.wrapper.annotation.SkipResponseWrap;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import org.cses.flow.controller.session.SessionModels.SessionView;
import org.pass.authx.security.session.AuthxSession;
import org.paas.session.bind.UserSession;

/**
 * User-facing HTTP adapter for the authenticated management session.
 */
@SkipResponseWrap
@Controller("/api")
public class SessionController {

    /**
     * 返回已选公司用户的管理会话信息。
     * @param session 认证绑定的非空 AuthX 会话
     * @return 当前公司、用户 ID 和显示名称
     * @throws RuntimeException 会话未选择公司时抛出
     */
    @Get("/session")
    public SessionView session(
            @UserSession AuthxSession session
    ) {
        session.requireActiveCompanyId();
        return SessionView.from(session);
    }
}
