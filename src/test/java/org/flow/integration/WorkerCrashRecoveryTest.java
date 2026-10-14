package org.flow.integration;

import org.flow.core.runners.FlowEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.flow.core.migration.MigrationRunner;
import org.flow.core.models.flows.State;
import org.flow.core.repositories.ExecutionRepositoryInterface;
import org.flow.core.utils.Await;
import io.micronaut.context.ApplicationContext;
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource;
import io.micronaut.inject.qualifiers.Qualifiers;
import javax.sql.DataSource;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class WorkerCrashRecoveryTest {
    @TempDir Path temporary;

    @Test
    void resumesClaimedTaskAfterWorkerJvmIsKilled() throws Exception {
        Path storage = Files.createDirectory(temporary.resolve("storage"));
        Path receipt = temporary.resolve("execution-id");
        Path log = temporary.resolve("worker.log");
        try (var context = ApplicationContext.run(WorkerCrashProbe.properties(storage.toString()), "test")) {
            context.getBean(MigrationRunner.class).runAlways();
            var source = context.getBean(DataSource.class, Qualifiers.byName("flow"));
            try (var connection = DelegatingDataSource.unwrapDataSource(source).getConnection()) {
                var database = DSL.using(connection, SQLDialect.POSTGRES);
                Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("flow.test-classpath"), WorkerCrashProbe.class.getName(),
                    storage.toString(), receipt.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
                try {
                    Await.await().atMost(Duration.ofSeconds(30)).until(() -> Files.exists(receipt) || !child.isAlive());
                    assertTrue(child.isAlive(), () -> read(log));
                    String executionId = Files.readString(receipt);
                    Await.await().atMost(Duration.ofSeconds(15)).until(() -> database.fetchCount(DSL.table("worker_job_running")) > 0);
                    var executions = context.getBean(ExecutionRepositoryInterface.class);
                    Await.await().atMost(Duration.ofSeconds(15)).until(() -> executions.findById("main", executionId)
                        .map(execution -> execution.getTaskRunList() != null && !execution.getTaskRunList().isEmpty()
                            && execution.getTaskRunList().getFirst().getState().isRunning())
                        .orElse(false));
                    // The lease and queue ACK have committed and the task is actually RUNNING.
                    child.destroyForcibly();
                    assertTrue(child.waitFor(10, TimeUnit.SECONDS));
                    var engine = context.getBean(FlowEngine.class);
                    engine.start();
                    try {
                        var result = Await.await().atMost(Duration.ofSeconds(60)).until(
                            () -> executions.findById("main", executionId).orElse(null),
                            execution -> execution != null && execution.getState().isTerminated());
                        assertEquals(State.Type.SUCCESS, result.getState().getCurrent());
                        assertTrue(result.getTaskRunList().getFirst().getAttempts().stream().anyMatch(attempt ->
                            attempt.getState().getHistories().stream().anyMatch(history -> history.getState() == State.Type.RESUBMITTED)));
                        Await.await().atMost(Duration.ofSeconds(10)).until(() -> database.fetchCount(DSL.table("worker_job_running")) == 0);
                    } finally {
                        engine.close();
                    }
                } finally {
                    if (child.isAlive()) {
                        child.destroyForcibly();
                        child.waitFor(10, TimeUnit.SECONDS);
                    }
                }
            }
        }
    }

    private static String read(Path log) {
        try {
            return Files.readString(log);
        } catch (Exception e) {
            return e.toString();
        }
    }
}
