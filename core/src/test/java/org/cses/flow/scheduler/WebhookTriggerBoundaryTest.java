package org.cses.flow.scheduler;

import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.services.executions.WorkflowUcFixture;
import org.cses.flow.core.services.triggers.WebhookTriggerService;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

/** Webhook 的租户、请求输入和并发幂等边界，使用真实 Core/数据库/Broker。 */
class WebhookTriggerBoundaryTest {
    /**
     * 非法请求不创建实例，并发同键复用身份，输入变化拒绝，禁用后新请求不可启动。
     * @throws Exception 并发发布或等待结果失败时抛出
     */
    @Test
    void validatesRequestsAndDeduplicatesConcurrentCallsWithSchedulerDisabled() throws Exception {
        try (var fixture = WorkflowUcFixture.openWithProperties(Map.of("flow.scheduler.enabled", false))) {
            String yaml = """
                key: webhook-boundary
                inputs:
                  - key: source
                    type: STRING
                  - key: value
                    type: STRING
                triggers:
                  - key: incoming
                    type: org.cses.flow.extensions.triggers.WebhookTrigger
                    inputs:
                      source: static
                tasks:
                  - key: done
                    type: org.cses.flow.extensions.log.Log
                    message: done
                """;
            var flow = fixture.deploy(yaml);
            var service = fixture.bean(WebhookTriggerService.class);
            assertThrows(IllegalArgumentException.class,
                () -> service.fire(fixture.session(), flow.key(), "incoming", "", Map.of("value", "one")));
            assertThrows(IllegalArgumentException.class,
                () -> service.fire(fixture.sessionFor("foreign"), flow.key(), "incoming", "foreign", Map.of("value", "one")));
            assertThrows(IllegalArgumentException.class,
                () -> service.fire(fixture.session(), flow.key(), "incoming", "static-override", Map.of("source", "other", "value", "one")));
            assertThrows(RuntimeException.class,
                () -> service.fire(fixture.session(), flow.key(), "incoming", "bad-input", Map.of("value", 42)));
            assertEquals(0, fixture.executionCount());

            String id;
            try (var pool = Executors.newFixedThreadPool(2)) {
                var start = new CountDownLatch(1);
                var first = pool.submit(() -> { start.await(); return service.fire(fixture.session(), flow.key(), "incoming", "same", Map.of("value", "one")); });
                var second = pool.submit(() -> { start.await(); return service.fire(fixture.session(), flow.key(), "incoming", "same", Map.of("value", "one")); });
                start.countDown();
                id = first.get(15, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(id, second.get(15, java.util.concurrent.TimeUnit.SECONDS));
            }
            var execution = fixture.awaitStable(id);
            assertEquals(State.Type.SUCCESS, execution.state().current());
            assertEquals(Map.of("source", "static", "value", "one"), execution.inputs());
            assertTrue(execution.activeTaskRuns().isEmpty());
            assertThrows(IllegalArgumentException.class,
                () -> service.fire(fixture.session(), flow.key(), "incoming", "same", Map.of("value", "different")));
            fixture.flowService().save(fixture.session(), PublishFlowCommand.from(flow.key(),
                yaml.replace("key: incoming", "key: incoming\n    disabled: true")));
            fixture.flowService().save(fixture.session(), PublishFlowCommand.from(flow.key(), false));
            assertThrows(IllegalArgumentException.class,
                () -> service.fire(fixture.session(), flow.key(), "incoming", "new-disabled", Map.of("value", "one")));
            assertEquals(id, service.fire(fixture.session(), flow.key(), "incoming", "same", Map.of("value", "one")));
            assertEquals(1, fixture.executionCount());
        }
    }
}
