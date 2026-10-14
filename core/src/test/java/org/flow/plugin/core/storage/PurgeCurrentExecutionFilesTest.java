package org.flow.plugin.core.storage;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.flow.core.context.TestRunContextFactory;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.flows.Flow;

import jakarta.inject.Inject;

import static org.flow.core.tenant.TenantService.MAIN_TENANT;
import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
class PurgeCurrentExecutionFilesTest {
    @Inject
    private TestRunContextFactory runContextFactory;

    @Test
    void run() throws Exception {
        // create a file
        var flow = Flow.builder()
            .namespace("namespace")
            .id("flowId")
            .tenantId(MAIN_TENANT)
            .build();
        var runContext = runContextFactory.of(
            flow, Map.of(
                "execution", Map.of("id", "executionId"),
                "task", Map.of("id", "taskId"),
                "taskrun", Map.of("id", "taskRunId")
            )
        );
        var file = runContext.workingDir().createFile("test.txt", "Hello World".getBytes());
        runContext.storage().putFile(file.toFile());

        var purge = PurgeCurrentExecutionFiles.builder()
            .build();
        var output = purge.run(runContext);

        assertThat(output.getUris().size()).isEqualTo(2);
    }
}