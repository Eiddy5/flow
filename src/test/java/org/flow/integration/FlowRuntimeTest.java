package org.flow.integration;

import org.flow.core.runners.FlowEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;

import org.flow.core.contexts.FlowContext;
import org.flow.core.executor.command.Create;
import org.flow.core.executor.command.ExecutionCommand;
import org.flow.core.migration.MigrationRunner;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.GenericFlow;
import org.flow.core.models.flows.State;
import org.flow.core.queues.DispatchQueueInterface;
import org.flow.core.repositories.ExecutionRepositoryInterface;
import org.flow.core.services.ExecutionOutputService;
import org.flow.core.services.FlowService;
import org.flow.core.services.TaskOutputService;
import org.flow.core.utils.Await;
import io.micronaut.context.ApplicationContext;
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(startApplication = false, transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlowRuntimeTest implements TestPropertyProvider {
    @Inject ApplicationContext context;
    @Inject FlowEngine engine;
    @Inject @Named("flow") DataSource flowSource;
    @Inject @Named("default") DataSource controlSource;
    @Inject DispatchQueueInterface<ExecutionCommand> commands;
    @Inject ExecutionRepositoryInterface executions;
    private Path storage;
    private Map<String, String> propertiesSnapshot;

    /**
     * 创建仅本次测试使用的存储目录、端口和数据库 schema。
     * @return 原生服务运行所需的配置
     * @throws IllegalStateException 无法分配测试目录或端口时抛出
     */
    @Override
    public Map<String, String> getProperties() {
        if (propertiesSnapshot != null) {
            return propertiesSnapshot;
        }
        Map<String, String> properties = new HashMap<>(new FlowJdbcTest().getProperties());
        try {
            storage = Files.createTempDirectory("flow-runtime-");
        } catch (Exception exception) {
            throw new IllegalStateException("无法分配隔离的引擎运行环境", exception);
        }
        properties.put("datasources.flow.schema", "flow_runtime_acceptance");
        properties.put("datasources.flow.maximum-pool-size", "24");
        properties.put("flow.server-type", "STANDALONE");
        properties.put("flow.allocated-cpu-cores", "2");
        properties.put("flow.storage.type", "local");
        properties.put("flow.storage.local.base-path", storage.toString());
        properties.put("flow.server.termination-grace-period", "PT5S");
        properties.put("flow.server.service.purge.initial-delay", "PT1H");
        properties.put("flow.server.service.purge.fixed-delay", "P1D");
        properties.put("flow.task.outputs.limit", "1024");
        properties.put("flow.execution.outputs.limit", "1024");
        properties.put("flow.engine.worker-threads", "2");
        properties.put("flow.engine.start-timeout", "PT30S");
        propertiesSnapshot = Map.copyOf(properties);
        return propertiesSnapshot;
    }

    /**
     * 从调用应用 Java 调用经过原生队列、条件分支和外部 Task，再验证定时器及故障停止的所有权。
     * @throws Exception 任一原生流程或数据库验证失败时使测试失败
     */
    @Test
    void runsNativeTasksAndSchedulerWithoutClosingHost() throws Exception {
        try (var connection = DelegatingDataSource.unwrapDataSource(flowSource).getConnection();
             var controlConnection = DelegatingDataSource.unwrapDataSource(controlSource).getConnection()) {
            var flowDb = DSL.using(connection, SQLDialect.POSTGRES);
            var controlDb = DSL.using(controlConnection, SQLDialect.POSTGRES);
            assertNull(flowDb.fetchValue("select to_regclass('flows')"), "启动调用应用不得自动建库");
            context.getBean(MigrationRunner.class).runAlways();
            try {
                engine.start();
                engine.start();
                assertTrue(engine.isRunning());
                assertEquals(1, context.getBeansOfType(FlowContext.class).size());
                String largeOutput = "durable-output-".repeat(300);
                var flow = context.getBean(FlowService.class).create(GenericFlow.fromYaml("main", """
                    id: native_flow
                    namespace: migration.acceptance
                    tasks:
                      - id: external
                        type: org.flow.fixture.Echo
                        text: native-jdbc
                      - id: script
                        type: org.flow.fixture.ScriptProbe
                        taskRunner:
                          type: org.flow.fixture.LocalProcess
                        outputFiles:
                          - probe.txt
                        commands:
                          - echo '::{"outputs":{"value":"process-ok"}}::'
                          - echo process-file > probe.txt
                      - id: choose
                        type: org.flow.plugin.core.flow.If
                        condition: "{{ outputs.external.text == 'native-jdbc' }}"
                        then:
                          - id: selected
                            type: org.flow.plugin.core.log.Log
                            message: "{{ outputs.external.text }}"
                        else:
                          - id: rejected
                            type: org.flow.plugin.core.execution.Fail
                      - id: large
                        type: org.flow.fixture.Echo
                        text: "%s"
                      - id: readLarge
                        type: org.flow.plugin.core.log.Log
                        message: "{{ outputs.large.text | length }}"
                    outputs:
                      - id: exported
                        type: STRING
                        value: "{{ outputs.external.text }}"
                      - id: largeExport
                        type: STRING
                        value: "{{ outputs.large.text }}"
                    """.formatted(largeOutput)));
                Create command = Create.of(flow);
                commands.emit(command);
                Execution result = Await.await().atMost(Duration.ofSeconds(30)).until(
                    () -> executions.findById("main", command.executionId()).orElse(null),
                    value -> value != null && value.getState().isTerminated());
                assertEquals(State.Type.SUCCESS, result.getState().getCurrent());
                assertTrue(result.getTaskRunList().stream().anyMatch(task -> task.getTaskId().equals("selected")));
                assertFalse(result.getTaskRunList().stream().anyMatch(task -> task.getTaskId().equals("rejected")));
                // 移除 Web 日志广播后，原生日志仍须经 JDBC 队列写入调用应用 flow 数据源。
                var logStore = context.getBean(org.flow.core.repositories.LogDataStoreInterface.class);
                Await.await().atMost(Duration.ofSeconds(15)).until(() ->
                    logStore.findByExecutionIdAndTaskId("main", result.getId(), "selected", org.slf4j.event.Level.INFO)
                        .stream().anyMatch(log -> "native-jdbc".equals(log.getMessage())));
                var output = context.getBean(TaskOutputService.class).getOutputs(result.getTaskRunList().stream()
                    .filter(task -> task.getTaskId().equals("external")).findFirst().orElseThrow());
                assertEquals("native-jdbc", output.get("text"));
                var scriptOutput = context.getBean(TaskOutputService.class).getOutputs(result.getTaskRunList().stream()
                    .filter(task -> task.getTaskId().equals("script")).findFirst().orElseThrow());
                assertEquals(0, ((Number) scriptOutput.get("exitCode")).intValue());
                assertEquals("process-ok", ((Map<?, ?>) scriptOutput.get("vars")).get("value"));
                var scriptFile = java.net.URI.create(((Map<?, ?>) scriptOutput.get("outputFiles")).get("probe.txt").toString());
                try (var stream = context.getBean(org.flow.core.storages.StorageInterface.class).get("main", null, scriptFile)) {
                    assertEquals("process-file\n", new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
                assertEquals("native-jdbc", context.getBean(ExecutionOutputService.class).getOutputs(result).get("exported"));
                assertEquals(largeOutput, context.getBean(ExecutionOutputService.class).getOutputs(result).get("largeExport"));
                var largeTask = result.getTaskRunList().stream().filter(task -> task.getTaskId().equals("large"))
                    .findFirst().orElseThrow();
                var persistedOutput = context.getBean(org.flow.core.repositories.TaskOutputRepositoryInterface.class)
                    .findById("main", largeTask.getId()).orElseThrow();
                assertNull(persistedOutput.value(), "大输出不应继续内联在数据库中");
                assertNotNull(persistedOutput.uri());

                context.getBean(FlowService.class).create(GenericFlow.fromYaml("main", """
                    id: native_schedule
                    namespace: migration.acceptance
                    tasks:
                      - id: external
                        type: org.flow.fixture.Echo
                        text: scheduled
                    triggers:
                      - id: clock
                        type: org.flow.plugin.core.trigger.Schedule
                        cron: "*/2 * * * * *"
                        withSeconds: true
                    """));
                String scheduledId = Await.await().atMost(Duration.ofSeconds(30)).until(
                    () -> flowDb.fetch("select id from executions where flow_id = 'native_schedule' and state_current = 'SUCCESS'")
                        .getValues(0, String.class).stream().findFirst().orElse(null), value -> value != null);
                assertEquals("clock", executions.findById("main", scheduledId).orElseThrow().getTrigger().getId());
                assertNull(controlDb.fetchValue("select to_regclass('executions')"));
                assertNull(controlDb.fetchValue("select to_regclass('queues')"));

                context.getBean(FlowContext.class).shutdown();
                Await.await().atMost(Duration.ofSeconds(15)).until(() -> !engine.isRunning());
                engine.close();
                engine.close();
                assertThrows(IllegalStateException.class, engine::start);
                assertTrue(context.isRunning(), "引擎故障不能关闭调用应用 Context");
                for (var service : context.getBeansOfType(org.flow.core.server.Service.class)) {
                    assertEquals(org.flow.core.server.Service.ServiceState.TERMINATED_GRACEFULLY,
                        service.getState(), service.getClass().getName());
                }
                assertEquals(1, flowDb.fetchValue("select 1"));
                assertEquals(1, controlDb.fetchValue("select 1"));
                try (var freshFlow = DelegatingDataSource.unwrapDataSource(flowSource).getConnection();
                     var freshControl = DelegatingDataSource.unwrapDataSource(controlSource).getConnection()) {
                    assertTrue(freshFlow.isValid(2));
                    assertTrue(freshControl.isValid(2));
                }
                assertTrue(executions.findById("main", result.getId()).isPresent());
                context.close();
                try (var reopened = ApplicationContext.run(new HashMap<String, Object>(propertiesSnapshot), "test")) {
                    var restored = reopened.getBean(ExecutionRepositoryInterface.class).findById("main", result.getId()).orElseThrow();
                    assertEquals(State.Type.SUCCESS, restored.getState().getCurrent());
                    assertEquals(largeOutput, reopened.getBean(TaskOutputService.class).getOutputs(largeTask).get("text"));
                    assertEquals(largeOutput, reopened.getBean(ExecutionOutputService.class).getOutputs(restored).get("largeExport"));
                    assertTrue(reopened.getBean(org.flow.core.repositories.LogDataStoreInterface.class)
                        .findByExecutionIdAndTaskId("main", result.getId(), "selected", org.slf4j.event.Level.INFO)
                        .stream().anyMatch(log -> "native-jdbc".equals(log.getMessage())));
                }
            } finally {
                engine.close();
            }
        } finally {
            context.close();
            try (var connection = java.sql.DriverManager.getConnection(System.getenv("FLOW_TEST_URL"), "flow", "flow")) {
                DSL.using(connection).execute("drop schema if exists flow_runtime_acceptance cascade");
            }
            org.apache.commons.io.FileUtils.deleteDirectory(storage.toFile());
        }
    }
}
