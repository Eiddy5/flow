package org.flow.core.utils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.flow.core.contexts.FlowContext;
import org.flow.core.models.ServerType;
import org.flow.core.plugins.PluginRegistry;
import org.flow.core.storages.StorageInterface;

import static org.assertj.core.api.Assertions.assertThat;

class ThreadUncaughtExceptionHandlerTest {

    private FlowContext previousContext;
    @TempDir Path temporary;

    @BeforeEach
    void saveFlowContext() {
        try {
            previousContext = FlowContext.getContext();
        } catch (IllegalStateException e) {
            previousContext = null;
        }
    }

    @AfterEach
    void restoreFlowContext() {
        // do not leak the recording stub into later tests of the same fork
        FlowContext.setContext(previousContext);
    }

    @Test
    void shouldShutdownTheBoundContextNotTheStaticOne() {
        // Given: the JVM-global static points to another (newer) context
        var boundContext = new RecordingContext();
        var otherContext = new RecordingContext();
        FlowContext.setContext(otherContext);

        var handler = new ThreadUncaughtExceptionHandler(boundContext);

        // When
        handler.uncaughtException(Thread.currentThread(), new RuntimeException("boom"));

        // Then
        assertThat(boundContext.shutdownCalled).isTrue();
        assertThat(otherContext.shutdownCalled).isFalse();
    }

    @Test
    void shouldCaptureTheContextCurrentAtConstructionTime() {
        // Given: a handler created while the static points to the owning context
        var owningContext = new RecordingContext();
        FlowContext.setContext(owningContext);
        var handler = new ThreadUncaughtExceptionHandler();

        // When: a newer context replaced the static before the failure happens
        var newerContext = new RecordingContext();
        FlowContext.setContext(newerContext);
        handler.uncaughtException(Thread.currentThread(), new RuntimeException("boom"));

        // Then
        assertThat(owningContext.shutdownCalled).isTrue();
        assertThat(newerContext.shutdownCalled).isFalse();
    }

    /**
     * Exercises a production-environment failure in a child JVM so a process-exit regression
     * produces a precise assertion without terminating the test runner.
     * @throws Exception child process or output inspection failed
     */
    @Test
    void shouldKeepCallerProcessAliveOutsideTestEnvironment() throws Exception {
        Path output = temporary.resolve("uncaught-failure.log");
        Process process = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("flow.test-classpath"),
            ThreadUncaughtExceptionHandlerTest.class.getName())
            .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            String log = Files.readString(output);
            assertThat(process.exitValue()).withFailMessage(log).isZero();
            assertThat(log).contains("caller-process-alive");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Child-JVM probe: a production context must receive shutdown and return control to its caller.
     * @param args unused command-line arguments
     */
    public static void main(String[] args) {
        RecordingContext context = new RecordingContext("production");
        new ThreadUncaughtExceptionHandler(context)
            .uncaughtException(Thread.currentThread(), new IllegalStateException("native engine failure"));
        if (!context.shutdownCalled) {
            throw new AssertionError("engine shutdown was not requested");
        }
        System.out.println("caller-process-alive");
    }

    /** A context that records shutdown without owning application or process resources. */
    private static class RecordingContext extends FlowContext {
        private volatile boolean shutdownCalled = false;
        private String environment;

        /** Creates a recording context in the normal test environment. */
        private RecordingContext() {
            this("test");
        }

        /**
         * Creates a recording context for a specific runtime environment.
         * @param environment active environment name
         */
        private RecordingContext(String environment) {
            this.environment = environment;
        }

        @Override
        public void shutdown() {
            this.shutdownCalled = true;
        }

        @Override
        public Set<String> getEnvironments() {
            return Set.of(environment);
        }

        @Override
        public ServerType getServerType() {
            return ServerType.STANDALONE;
        }

        @Override
        public int getAllocatedCpuCores() {
            return 1;
        }

        @Override
        public Optional<Integer> getWorkerMaxNumThreads() {
            return Optional.empty();
        }

        @Override
        public void injectWorkerConfigs(Integer maxNumThreads) {
            // no-op
        }

        @Override
        public String getVersion() {
            return null;
        }

        @Override
        public EditionProvider.Edition getEdition() {
            return EditionProvider.Edition.OSS;
        }

        @Override
        public PluginRegistry getPluginRegistry() {
            return null;
        }

        @Override
        public StorageInterface getStorageInterface() {
            return null;
        }
    }
}
