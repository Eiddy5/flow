package org.flow.plugin.core.flow;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.LoadFlows;

import jakarta.inject.Inject;

@org.flow.core.junit.annotations.FlowTest(startRunner = true)
class FlowTest {
    @Inject
    FlowCaseTest flowCaseTest;

    @Test
    @LoadFlows(
        value = { "flows/valids/task-flow.yaml",
            "flows/valids/task-flow-inherited-labels.yaml",
            "flows/valids/switch.yaml" },
        tenantId = "waitsuccess"
    )
    void waitSuccess() throws Exception {
        flowCaseTest.waitSuccess("waitsuccess");
    }

    @Test
    @LoadFlows(
        value = { "flows/valids/task-flow.yaml",
            "flows/valids/task-flow-inherited-labels.yaml",
            "flows/valids/switch.yaml" },
        tenantId = "waitfailed"
    )
    void waitFailed() throws Exception {
        flowCaseTest.waitFailed("waitfailed");
    }

    @Test
    @LoadFlows(
        value = { "flows/valids/task-flow.yaml",
            "flows/valids/task-flow-inherited-labels.yaml",
            "flows/valids/switch.yaml" },
        tenantId = "nolabels"
    )
    void noLabels() throws Exception {
        flowCaseTest.noLabels("nolabels");
    }
}
