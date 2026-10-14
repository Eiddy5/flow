package org.flow.core.runners;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.ExecuteFlow;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.TaskRun;
import org.flow.core.models.executions.TaskRunAttempt;
import org.flow.core.models.flows.State;
import org.flow.core.storages.StorageInterface;

import jakarta.inject.Inject;

import static org.flow.core.tenant.TenantService.MAIN_TENANT;
import static org.assertj.core.api.Assertions.assertThat;

@FlowTest(startRunner = true)
public class LogToFileTest {
    @Inject
    private StorageInterface storage;

    @Test
    @ExecuteFlow("flows/valids/log-to-file.yaml")
    void task(Execution execution) throws Exception {
        assertThat(execution.getTaskRunList()).hasSize(1);
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        TaskRun taskRun = execution.getTaskRunList().getFirst();
        assertThat(taskRun.getAttempts()).hasSize(1);
        TaskRunAttempt attempt = taskRun.getAttempts().getFirst();
        assertThat(attempt.getLogFile()).isNotNull();

        InputStream inputStream = storage.get(MAIN_TENANT, "org.flow.tests", attempt.getLogFile());
        List<String> strings = IOUtils.readLines(inputStream, StandardCharsets.UTF_8);
        assertThat(strings).isNotNull();
        assertThat(strings.size()).isEqualTo(1);
        assertThat(strings.getFirst()).contains("INFO");
        assertThat(strings.getFirst()).contains("Hello World!");
    }
}
