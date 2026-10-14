package org.flow.core.repositories;

import java.io.File;
import java.io.FileInputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.LogEntry;
import org.flow.core.models.executions.TaskRun;
import org.flow.core.models.flows.Flow;
import org.flow.core.models.flows.State;
import org.flow.core.runners.RunContext;
import org.flow.core.runners.RunContextFactory;
import org.flow.core.services.ExecutionService;
import org.flow.core.utils.IdUtils;
import org.flow.core.utils.TestsUtils;
import org.flow.plugin.core.debug.Return;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@MicronautTest
public abstract class AbstractExecutionServiceTest {
    @Inject
    ExecutionService executionService;

    @Inject
    ExecutionRepositoryInterface executionRepository;

    @Inject
    LogDataStoreInterface logRepository;

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void purge() throws Exception {
        String tenantId = TestsUtils.randomTenant();
        URL resource = AbstractExecutionServiceTest.class.getClassLoader().getResource("application-test.yml");
        File tempFile = File.createTempFile("test", "");
        Files.copy(new FileInputStream(Objects.requireNonNull(resource).getFile()), tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

        State state = new State().withState(State.Type.RUNNING).withState(State.Type.SUCCESS);

        Flow flow = Flow.builder()
            .namespace("org.flow.test")
            .id("abc")
            .tenantId(tenantId)
            .revision(1)
            .build();

        Execution execution = Execution
            .builder()
            .id(IdUtils.create())
            .tenantId(tenantId)
            .state(state)
            .flowId(flow.getId())
            .namespace(flow.getNamespace())
            .flowRevision(flow.getRevision())
            .build();

        Return task = Return.builder().id(IdUtils.create()).type(Return.class.getName()).build();

        TaskRun taskRun = TaskRun
            .builder()
            .namespace(flow.getNamespace())
            .id(IdUtils.create())
            .tenantId(tenantId)
            .executionId(execution.getId())
            .flowId(flow.getId())
            .taskId(task.getId())
            .state(state)
            .build();

        RunContext runContext = runContextFactory.of(
            flow,
            task,
            execution,
            taskRun
        );

        execution.withInputs(Map.of("test", runContext.storage().putFile(tempFile)));

        executionRepository.save(execution);

        for (int i = 0; i < 10; i++) {
            logRepository.save(
                LogEntry.builder()
                    .executionId(execution.getId())
                    .tenantId(tenantId)
                    .timestamp(Instant.now())
                    .message("Message " + i)
                    .flowId(flow.getId())
                    .level(Level.INFO)
                    .namespace(flow.getNamespace())
                    .build()
            );
        }

        ExecutionService.PurgeResult purge = executionService.purge(
            true,
            true,
            true,
            true,
            tenantId,
            flow.getNamespace(),
            flow.getId(),
            null,
            ZonedDateTime.now(),
            null,
            100
        );

        assertThat(purge.getExecutionsCount()).isEqualTo(1);
        assertThat(purge.getLogsCount()).isEqualTo(10);
        assertThat(purge.getStoragesCount()).isEqualTo(5);

        purge = executionService.purge(
            true,
            true,
            true,
            true,
            tenantId,
            flow.getNamespace(),
            flow.getId(),
            null,
            ZonedDateTime.now(),
            null,
            100
        );

        assertThat(purge.getExecutionsCount()).isZero();
    }
}