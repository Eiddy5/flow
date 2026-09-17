package org.cses.flow.controller.triggers;

import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.Test;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 HTTP 的认证与必填幂等头拒绝边界，不替代 UC16 主测试。 */
class WebhookHttpBoundaryTest {
    /**
     * 无有效身份或缺少幂等头时不能受理 Webhook，也不能产生运行实例。
     * @throws Exception HTTP 请求失败时抛出
     */
    @Test
    void rejectsMissingAuthenticationAndIdempotencyHeaderWithoutExecution() throws Exception {
        try (var fixture = WebhookHttpTestSupport.open(); var client = HttpClient.newHttpClient()) {
            var flow = fixture.deploy("""
                key: webhook-rejections
                triggers:
                  - key: incoming
                    type: org.cses.flow.extensions.triggers.WebhookTrigger
                tasks:
                  - key: done
                    type: org.cses.flow.extensions.log.Log
                    message: done
                """);
            var uri = fixture.bean(EmbeddedServer.class).getURI().resolve("/triggers/flows/" + flow.key() + "/incoming");
            var anonymous = HttpRequest.newBuilder(uri).header("Idempotency-Key", "anonymous")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            var denied = client.send(anonymous, HttpResponse.BodyHandlers.ofString());
            assertTrue(denied.statusCode() == 401 || denied.statusCode() == 403, denied.statusCode() + ": " + denied.body());
            var missingKey = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + fixture.bean(WebhookHttpTestSupport.Identity.class).token)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            var invalid = client.send(missingKey, HttpResponse.BodyHandlers.ofString());
            assertEquals(400, invalid.statusCode(), invalid.body());
            assertEquals(0, fixture.executionCount());
        }
    }
}
