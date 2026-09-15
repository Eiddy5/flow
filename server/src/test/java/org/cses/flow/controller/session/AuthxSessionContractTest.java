package org.cses.flow.controller.session;

import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.utils.SessionUtil;
import org.cses.flow.executor.commands.Create;
import org.junit.jupiter.api.Test;
import org.pass.authx.security.session.AuthxSession;
import org.pass.authx.organization.common.exception.OrganizationException;
import org.pass.authx.security.session.AuthxSessionFactory;
import org.pass.authx.security.session.AuthxUser;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 AuthX 会话类型验证 Flow 的会话边界；不代替真实登录验证。 */
class AuthxSessionContractTest {

    /** 响应过滤器所需的诊断类必须进入运行时，避免首个 HTTP 请求发生类缺失。 */
    @Test
    void includesResponseFilterRuntimeDependency() {
        assertDoesNotThrow(() -> Class.forName("org.paas.diagnostic.diagnostic.DiagnosticName"));
    }

    /** 用户不携带公司字段时，Flow 仍从已选择公司的会话创建命令和领域。 */
    @Test
    void acceptsAuthxUserWithoutLegacyCompanyFields() {
        AuthxSession session = new AuthxSession();
        session.initUser("actor", "Alice");
        session.setCompanyId("company");
        session.getUser().onSessionBound();

        assertNull(session.getUser().getCompanyId());
        ActorRef actor = SessionUtil.user(session);
        assertEquals("actor", actor.id());
        assertEquals("Alice", actor.name().orElseThrow());
        Create command = Create.from(session, "execution", "flow", 1, Map.of());
        assertEquals("company", command.companyId());
        assertEquals("actor", command.actorId());
        Execution execution = Execution.create("execution", session, "flow", 1, Map.of());
        assertEquals("company", execution.companyId());
        assertEquals("actor", execution.creator().id());
        var view = new SessionController().session(session);
        assertEquals("company", view.getCompanyId());
        assertEquals("actor", view.getUserId());
        assertEquals("Alice", view.getUserName());
    }

    /** 未选公司会话在 HTTP 和 Core 两个入口都不能执行租户操作。 */
    @Test
    void rejectsMissingCompanyContext() {
        AuthxSession session = new AuthxSession();
        session.initUser("actor", "Alice");
        assertThrows(OrganizationException.class, () -> new SessionController().session(session));
        assertThrows(IllegalArgumentException.class,
                () -> Create.from(session, "execution", "flow", 1, Map.of()));
    }

    /** 异步恢复使用工厂的真实 AuthX 类型，既覆盖查到用户也覆盖最小用户创建。 */
    @Test
    void restoresFactoryTypesWithoutLegacyUserCasts() {
        for (boolean found : List.of(false, true)) {
            AuthxSessionFactory factory = new AuthxSessionFactory(null) {
                /** 隔离账号查询，保留 AuthX 工厂自身的会话和用户创建方法。
                 * @param ids 消费者请求的用户 ID
                 * @return 已找到用户或空集合
                 */
                @Override
                public List<AuthxUser> buildSessionUser(List<String> ids) {
                    assertEquals(List.of("actor"), ids);
                    return found ? List.of(user()) : List.of();
                }
            };
            AuthxSession session = SessionUtil.restore(factory, "company", "actor");
            assertEquals("company", session.getCompanyId());
            assertEquals("actor", SessionUtil.user(session).id());
            assertNull(session.getUser().getCompanyId());
            assertNull(session.getCompanyMember());
            assertNull(session.getAuthSessionId());
        }
    }
}
