package org.cses.flow.controller.triggers;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.inject.Singleton;
import org.cses.flow.core.services.executions.WorkflowUcFixture;
import org.paas.common.util.StringUtil;
import org.paas.session.bind.SessionAuthenticator;
import org.pass.authx.security.session.AuthxSession;
import org.pass.authx.security.session.AuthxUser;

import java.util.Map;

/** HTTP 测试只替代验收范围外的身份认证，保留生产认证过滤器与会话参数绑定。 */
public class WebhookHttpTestSupport {
    /** 禁止创建静态辅助类实例。 */
    private WebhookHttpTestSupport() {}

    /**
     * 以真实 PostgreSQL、Pulsar 和随机端口启动 HTTP 上下文，关闭定时器仍保留触发事件运行时。
     * @return 调用方负责关闭的真实服务夹具
     */
    public static WorkflowUcFixture open() {
        var fixture = WorkflowUcFixture.openWithProperties(Map.of(
            "flow.webhook.http-test", true,
            "flow.scheduler.enabled", false,
            "micronaut.server.port", -1,
            "datasources.flow.url", System.getenv("FLOW_POSTGRES_TEST_URL"),
            "datasources.flow.username", System.getenv("FLOW_POSTGRES_TEST_USER"),
            "datasources.flow.password", System.getenv("FLOW_POSTGRES_TEST_PASSWORD"),
            "datasources.flow.driver-class-name", "org.postgresql.Driver",
            "datasources.flow.maximum-pool-size", 4
        ));
        try {
            var authenticator = fixture.bean(Identity.class);
            authenticator.session = new AuthxSession();
            authenticator.session.initUser(fixture.session().getUser().getId(), "Webhook test user");
            authenticator.session.setCompanyId(fixture.session().getCompanyId());
            fixture.bean(EmbeddedServer.class).start();
            return fixture;
        } catch (RuntimeException failure) {
            fixture.close();
            throw failure;
        }
    }

    /** 每个上下文独占随机凭据，缺失或错误凭据不提供会话。 */
    @Singleton
    @Requires(property = "flow.webhook.http-test", value = "true")
    @io.micronaut.context.annotation.Replaces(org.pass.authx.security.authentication.AuthxSessionAuthenticator.class)
    public static class Identity implements SessionAuthenticator<AuthxSession, AuthxUser> {
        String token = StringUtil.newId();
        AuthxSession session;
        /**
         * 核验测试随机凭据并返回真实 AuthX 会话；不提供默认身份。
         * @param request 当前真实 HTTP 请求
         * @return 凭据匹配时返回本测试会话，否则为空
         */
        @Override
        public AuthxSession authenticate(HttpRequest<?> request) {
            if (!("Bearer " + token).equals(request.getHeaders().get("Authorization"))) return null;
            String companyId = session.getCompanyId();
            session.init(request);
            session.setCompanyId(companyId);
            return session;
        }
    }
    /** 仅隔离账号资料查询，保留真实 AuthX 会话及用户构造方法。 */
    @Singleton
    @Requires(property = "flow.webhook.http-test", value = "true")
    @io.micronaut.context.annotation.Replaces(org.pass.authx.security.session.AuthxSessionFactory.class)
    public static class Accounts extends org.pass.authx.security.session.AuthxSessionFactory {
        /** 不连接验收范围外的认证账户数据库。 */
        public Accounts() { super(null); }

        /**
         * 恢复指定测试用户资料，用户ID始终沿用真实触发事件。
         * @param ids 由执行命令传入的可信用户ID列表
         * @return 对应的真实AuthX用户模型，不访问外部账号库
         */
        @Override
        public java.util.List<AuthxUser> buildSessionUser(java.util.List<String> ids) {
            return ids.stream().map(id -> {
                var user = user();
                user.setId(id);
                user.setName("Webhook test user");
                return user;
            }).toList();
        }
    }

    /** 隔离验收范围外的诊断管理存储；误调用时明确失败。 */
    @Singleton
    @Requires(property = "flow.webhook.http-test", value = "true")
    @io.micronaut.context.annotation.Context
    @io.micronaut.context.annotation.Replaces(org.paas.diagnostic.service.impl.ServerServiceImpl.class)
    public static class Diagnostics implements org.paas.diagnostic.service.ServerService<AuthxSession, AuthxUser> {
        /** @param session 未使用的会话 @param param 未使用的参数 @return 不返回结果 @throws UnsupportedOperationException 本测试不提供诊断接口 */
        public org.paas.json.JsonObject websocketCount(AuthxSession session, org.paas.json.JsonObject param) { throw new UnsupportedOperationException(); }
        /** @param session 未使用的会话 @return 不返回结果 @throws UnsupportedOperationException 本测试不提供诊断接口 */
        public org.paas.json.JsonObjects readDebugs(AuthxSession session) { throw new UnsupportedOperationException(); }
        /** @param session 未使用的会话 @param param 未使用的参数 @return 不返回结果 @throws UnsupportedOperationException 本测试不提供诊断接口 */
        public org.paas.json.JsonObject setDebug(AuthxSession session, org.paas.json.JsonObject param) { throw new UnsupportedOperationException(); }
        /** @param session 未使用的会话 @return 不返回结果 @throws UnsupportedOperationException 本测试不提供诊断接口 */
        public org.paas.json.JsonObject aboutMe(AuthxSession session) { throw new UnsupportedOperationException(); }
        /** @param keyword 未使用的关键词 @return 不返回结果 @throws UnsupportedOperationException 本测试不提供诊断接口 */
        public java.util.List<io.micronaut.web.router.UriRouteInfo<?, ?>> readServices(String keyword) { throw new UnsupportedOperationException(); }
    }

}
