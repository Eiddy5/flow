package org.flow.core.utils;

import java.util.List;
import java.util.Objects;

import org.flow.core.models.session.ActorRef;
import org.paas.session.Session;
import org.paas.session.SessionFactory;
import org.paas.session.SessionUser;

/** 对齐 main 的 Session 合同；租户由 Session 访问器决定，不能从用户对象推断。 */
public class SessionUtil {
    /** 工具类不创建实例。 */
    private SessionUtil() {
    }

    /** 返回非空租户；缺少 Session 或租户时拒绝调用。 */
    public static String company(Session<? extends SessionUser> session) {
        Objects.requireNonNull(session, "session 不能为空");
        return required(session.getCompanyId(), "Session.companyId");
    }

    /** 提取非空操作者；名称缺省时使用 Session 的用户名。 */
    public static ActorRef user(Session<? extends SessionUser> session) {
        company(session);
        var user = Objects.requireNonNull(session.getUser(), "Session.user 不能为空");
        String id = required(user.getId(), "Session.user.id");
        String name = user.getName();
        return ActorRef.from(id, name == null || name.isBlank() ? session.getUserName() : name);
    }

    /**
     * 通过宿主工厂恢复后台命令的租户与操作者，不恢复登录凭证或授权状态。
     * 工厂返回的用户必须匹配 actorId；无用户资料时创建最小用户，并执行绑定回调。
     */
    public static <S extends Session<U>, U extends SessionUser> S restore(
        SessionFactory<S, U> factory, String companyId, String actorId) {
        Objects.requireNonNull(factory, "SessionFactory 不能为空");
        String tenant = required(companyId, "companyId");
        String actor = required(actorId, "actorId");
        S session = Objects.requireNonNull(factory.session(), "SessionFactory.session 不能为空");
        List<U> users = factory.buildSessionUser(List.of(actor));
        U user = users == null ? null : users.stream()
            .filter(Objects::nonNull).filter(candidate -> actor.equals(candidate.getId())).findFirst().orElse(null);
        if (user == null) {
            user = Objects.requireNonNull(factory.user(), "SessionFactory.user 不能为空");
        }
        user.setId(actor);
        user.setCompanyId(tenant);
        session.setCompanyId(tenant);
        session.setUser(user);
        user.onSessionBound();
        if (!tenant.equals(company(session)) || !actor.equals(user(session).id())) {
            throw new IllegalStateException("SessionFactory 恢复的租户或操作者与命令不一致");
        }
        return session;
    }

    /** 校验并规范化必填标识，禁止空白标识进入仓储或队列。 */
    public static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }
}
