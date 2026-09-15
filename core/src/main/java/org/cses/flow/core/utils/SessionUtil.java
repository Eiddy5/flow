package org.cses.flow.core.utils;

import org.cses.flow.core.domains.ActorRef;
import org.paas.session.Session;
import org.paas.session.SessionUser;
import org.paas.session.SessionFactory;

import java.util.List;

public final class SessionUtil {

    /** 禁止实例化无状态会话工具。 */
    private SessionUtil() {
    }

    /**
     * 从会话读取必填公司 ID；不读取用户上的旧公司字段。
     * @param session 非空的可信会话
     * @return 去除首尾空白的公司 ID
     * @throws IllegalArgumentException 公司 ID 为空时抛出
     * @throws NullPointerException 会话为空时抛出
     */
    public static String company(Session<? extends SessionUser> session) {
        Session<? extends SessionUser> requiredSession = RequiredUtil.required(
            session,
            "session is required"
        );
        return RequiredUtil.required(
            requiredSession.getCompanyId(),
            "session.companyId is required"
        ).trim();
    }

    /**
     * 从可信会话提取操作者；公司身份由会话提供，用户只提供全局身份。
     * @param session 已验证身份且具有公司上下文的非空会话
     * @return 用户 ID 与显示名称组成的操作者
     * @throws IllegalArgumentException 公司 ID 或用户 ID 为空时抛出
     * @throws NullPointerException 会话或用户为空时抛出
     */
    public static ActorRef user(Session<? extends SessionUser> session) {
        company(session);
        SessionUser sessionUser = RequiredUtil.required(
            session.getUser(),
            "current user is required"
        );
        String userId = RequiredUtil.required(
            sessionUser.getId(),
            "current user id is required"
        );
        String actorName = sessionUser.getName();
        if (actorName == null || actorName.isBlank()) {
            actorName = session.getUserName();
        }
        return ActorRef.create(userId, actorName);
    }

    /**
     * 通过当前工厂恢复异步消息的最小租户与操作者上下文，不恢复登录授权或公司成员资料。
     * @param factory 当前应用的非空会话工厂
     * @param companyId 消息携带的非空公司 ID
     * @param actorId 消息携带的非空操作者 ID
     * @param <S> 工厂创建的会话类型
     * @param <U> 工厂创建的用户类型
     * @return 与工厂类型一致的新会话
     * @throws IllegalArgumentException 公司 ID 或操作者 ID 为空时抛出
     */
    public static <S extends Session<U>, U extends SessionUser> S restore(
            SessionFactory<S, U> factory, String companyId, String actorId) {
        RequiredUtil.required(companyId, "session.companyId is required");
        RequiredUtil.required(actorId, "current user id is required");
        S session = factory.session();
        List<U> users = factory.buildSessionUser(List.of(actorId));
        U user = users == null || users.isEmpty() ? factory.user() : users.getFirst();
        user.setId(actorId);
        user.setCompanyId(companyId);
        session.setCompanyId(companyId);
        session.setUser(user);
        user.onSessionBound();
        return session;
    }
}
