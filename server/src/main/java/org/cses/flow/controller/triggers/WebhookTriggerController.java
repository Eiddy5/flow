package org.cses.flow.controller.triggers;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import org.cses.flow.core.services.triggers.WebhookTriggerService;
import org.paas.response.wrapper.annotation.SkipResponseWrap;
import org.paas.session.bind.UserSession;
import org.pass.authx.security.session.AuthxSession;
import java.util.Map;

/** Webhook 的已认证 HTTP 入口，租户来自会话。 */
@SkipResponseWrap
@Controller("/triggers")
public class WebhookTriggerController {
    private WebhookTriggerService service;
    /**
     * @param service 持久化 Webhook 命中的 Core 入口
     */
    public WebhookTriggerController(WebhookTriggerService service) { this.service = service; }
    /**
     * 持久化请求后返回 202 和稳定实例身份，不等待流程完成。
     * @param session 已认证会话
     * @param flowKey 目标流程
     * @param triggerKey 触发器名称
     * @param requestId 同一次请求的幂等键
     * @param inputs JSON 输入对象
     * @return 异步受理回执
     */
    @Post("/flows/{flowKey}/{triggerKey}")
    public HttpResponse<Map<String, String>> fire(@UserSession AuthxSession session, String flowKey, String triggerKey,
            @Header("Idempotency-Key") String requestId, @Body Map<String, Object> inputs) {
        session.requireActiveCompanyId();
        return HttpResponse.<Map<String, String>>accepted().body(Map.of("executionId", service.fire(session, flowKey, triggerKey, requestId, inputs)));
    }
    /**
     * @param failure 请求参数或命中被拒绝的原因
     * @return 不包含内部堆栈的 400 响应
     */
    @Error(exception = IllegalArgumentException.class)
    public HttpResponse<Map<String, String>> invalid(IllegalArgumentException failure) {
        return HttpResponse.badRequest(Map.of("message", failure.getMessage()));
    }
    /**
     * 将 Flow 输入绑定失败转换为请求错误。
     * @param failure 领域输入校验失败
     * @return 不包含内部堆栈的 400 响应
     */
    @Error(exception = org.cses.flow.core.exceptions.WorkflowException.class)
    public HttpResponse<Map<String, String>> invalidFlow(org.cses.flow.core.exceptions.WorkflowException failure) {
        return HttpResponse.badRequest(Map.of("message", failure.getMessage()));
    }

}
