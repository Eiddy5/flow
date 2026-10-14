package org.flow.plugin.core.http;

import java.time.Duration;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.junit.annotations.LoadFlowsWithTenant;
import org.flow.core.runners.Scheduler;
import org.flow.core.runners.TestRunnerUtils;

import jakarta.inject.Inject;

@FlowTest(startRunner = true, startScheduler = true)
class TriggerTest {
    @Inject
    private TestRunnerUtils runnerUtils;

    @Inject
    protected Scheduler scheduler;

    @Test
    @LoadFlowsWithTenant({ "flows/valids/http-listen.yaml" })
    void shouldExecuteFlowForHttpTrigger(String tenantId) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).until(() -> scheduler.isActive());

        runnerUtils.awaitFlowExecution(tenantId, "org.flow.tests", "http-listen", Duration.ofSeconds(20));
    }

    @Test
    @LoadFlowsWithTenant({ "flows/valids/http-listen-encrypted.yaml" })
    void shouldExecuteFlowForHttpTriggerWithEncryptedBody(String tenantId) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).until(() -> scheduler.isActive());

        runnerUtils.awaitFlowExecution(tenantId, "org.flow.tests", "http-listen-encrypted", Duration.ofSeconds(20));
    }
}
