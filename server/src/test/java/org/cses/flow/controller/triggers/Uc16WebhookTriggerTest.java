package org.cses.flow.controller.triggers;

import io.micronaut.runtime.server.EmbeddedServer;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.extensions.triggers.WebhookTrigger;
import org.junit.jupiter.api.Test;
import org.paas.common.util.StringUtil;
import org.paas.json.JsonObject;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/** docs/uc/flow/UC-16 用户通过 Webhook 启动 Flow.md 的唯一主测试。 */
class Uc16WebhookTriggerTest {
    /**
     * 发送一次合法真实 HTTP 请求，核对受理编号及完整运行终态。
     * @throws Exception HTTP 传输或响应解码失败时抛出
     */
    @Test
    void s1AuthenticatedHttpWebhookStartsOneCompleteExecution() throws Exception {
        try (var fixture = WebhookHttpTestSupport.open(); var client = HttpClient.newHttpClient()) {
            var flow = fixture.deploy("""
                key: webhook-uc
                inputs:
                  - key: message
                    type: STRING
                triggers:
                  - key: incoming
                    type: org.cses.flow.extensions.triggers.WebhookTrigger
                tasks:
                  - key: first
                    type: org.cses.flow.extensions.log.Log
                    message: first
                  - key: second
                    type: org.cses.flow.extensions.log.Log
                    message: second
                """);
            var selected = fixture.flowService().flow(fixture.session(), flow.key(), flow.version()).orElseThrow();
            assertInstanceOf(WebhookTrigger.class, selected.triggers().getFirst());
            assertEquals(0, fixture.executionCount());
            var uri = fixture.bean(EmbeddedServer.class).getURI().resolve("/triggers/flows/" + flow.key() + "/incoming");
            var request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + fixture.bean(WebhookHttpTestSupport.Identity.class).token)
                .header("Idempotency-Key", StringUtil.newId()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"from-http\"}")).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(202, response.statusCode(), response.body());
            var body = JsonObject.Parse(response.body());
            String executionId = body.getString("executionId");
            assertNotNull(executionId, response.body());
            var execution = fixture.awaitStable(executionId);
            assertEquals(1, fixture.executionCount());
            assertEquals(flow.key(), execution.flowKey());
            assertEquals(flow.version(), execution.flowVersion());
            assertEquals("from-http", execution.inputs().get("message"));
            assertEquals(State.Type.SUCCESS, execution.state().current());
            assertEquals(flow.tasks().stream().map(task -> task.id()).toList(),
                execution.taskRuns().stream().map(run -> run.taskId()).toList());
            assertTrue(execution.taskRuns().stream().allMatch(run -> run.state().current() == State.Type.SUCCESS));
            assertTrue(execution.activeTaskRuns().isEmpty());
            assertTrue(execution.taskRuns().stream().noneMatch(run -> run.state().isPaused()));
        }
    }
}
