package org.flow.core.utils;

import java.util.Objects;

import org.flow.core.models.session.ActorRef;
import org.paas.session.Session;
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

    /** 校验并规范化必填标识，禁止空白标识进入仓储或队列。 */
    public static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }
}
