package org.cses.flow.controller.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.runtime.server.EmbeddedServer;
import org.cses.flow.core.plugins.TestNotificationTask;
import org.cses.flow.extensions.tasks.AutomaticTask;
import org.cses.flow.infrastructure.datapilot.DemoPostgresJooqAdapter;
import org.cses.flow.infrastructure.session.DemoSessionArgumentBinder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowDemoControllerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void platformManagedDemoUsesLocalSessionWithoutStandalonePostgres()
        throws IOException {

        try (EmbeddedServer server = startPlatformManagedDemoServer();
             HttpClient client = HttpClient.create(server.getURL())) {

            String page = client.toBlocking().retrieve(
                HttpRequest.GET("/demo/index.html")
            );
            assertTrue(page.contains("Flow Studio Demo"));

            JsonNode dataTypes = get(client, "/api/demo/data-types");
            assertEquals(9, dataTypes.size());
            JsonNode session = get(client, "/api/demo/session");
            assertEquals("flow-demo", session.get("companyId").asText());
            assertEquals("demo-user", session.get("userId").asText());
            assertFalse(
                server.getApplicationContext().containsBean(
                    DemoPostgresJooqAdapter.class
                )
            );
            assertTrue(
                server.getApplicationContext().containsBean(
                    DemoSessionArgumentBinder.class
                )
            );

            JsonNode plugins = get(client, "/api/plugins");
            JsonNode automatic = findTaskPlugin(
                plugins,
                AutomaticTask.class.getCanonicalName()
            );
            assertNotNull(automatic);
            assertEquals("自动任务", automatic.get("title").asText());
            assertEquals(
                "org.cses.flow.core.domains.tasks.Task",
                automatic.get("baseType").asText()
            );
            assertEquals(
                AutomaticTask.class.getPackageName(),
                automatic.get("packageName").asText()
            );
            JsonNode notification = findTaskPlugin(
                plugins,
                TestNotificationTask.class.getCanonicalName()
            );
            assertNotNull(notification);
            assertEquals(
                TestNotificationTask.class.getPackageName(),
                notification.get("packageName").asText()
            );

            JsonNode details = get(
                client,
                "/api/plugins/"
                    + "org.cses.flow.extensions.tasks.AutomaticTask"
            );
            assertEquals(
                "org.cses.flow.extensions.tasks.AutomaticTask",
                details.get("metadata").get("type").asText()
            );
            assertEquals(
                AutomaticTask.class.getPackageName(),
                details.get("metadata").get("packageName").asText()
            );
            JsonNode schema = details.get("schema");
            assertFalse(schema.get("additionalProperties").asBoolean());
            assertFalse(schema.get("properties").has("id"));
            assertEquals(
                "org.cses.flow.extensions.tasks.AutomaticTask",
                schema.get("properties").get("type").get("const").asText()
            );
        }
    }

    @Test
    void supportsCompleteDraftDeployAndRunLifecycleOverHttp()
        throws IOException {

        try (EmbeddedServer server = startServer();
             HttpClient client = HttpClient.create(server.getURL())) {

            String page = client.toBlocking().retrieve(
                HttpRequest.GET("/demo/index.html")
            );
            assertTrue(page.contains("Flow Studio Demo"));
            String script = client.toBlocking().retrieve(
                HttpRequest.GET("/demo/demo.js")
            );
            assertTrue(script.contains("添加第一个流程 Task"));
            assertTrue(script.contains("添加流程 Task"));
            assertTrue(script.contains("添加后续 Task"));
            assertTrue(script.contains("添加子 Task"));
            assertTrue(script.contains("data-placement=\"flow\""));
            assertTrue(script.contains("data-placement=\"after\""));
            assertTrue(script.contains("data-placement=\"child\""));
            assertTrue(
                script.contains("新 Task 会直接写入 Flow.tasks")
            );
            assertTrue(
                script.contains(
                    "新 Task 会插入当前 Task 之后并保持同一层级"
                )
            );
            assertTrue(
                script.contains(
                    "同级后续会成为新的并行分支"
                )
            );
            assertTrue(
                script.contains(
                    "location.tasks.splice(nextIndex, 0, task)"
                )
            );
            assertFalse(
                script.contains("data-placement=\"sequence\"")
            );
            assertTrue(script.contains("同意并继续"));
            assertTrue(script.contains("每个 Input 独立定义 key 和 type"));
            assertTrue(script.contains("data-action=\"add-data-item\""));
            assertTrue(script.contains("创建并行节点与两个分支"));
            assertTrue(script.contains("renderRegisteredTaskOptions"));
            assertTrue(script.contains("plugin.packageName"));
            assertTrue(script.contains("pluginPackage"));
            assertTrue(script.contains("confirm-create-task"));
            assertTrue(script.contains("来自插件注册表"));
            assertFalse(script.contains("FALLBACK_TASK_PLUGINS"));
            assertTrue(script.contains("pluginApi"));
            assertTrue(script.contains("loadPluginDetails"));
            assertTrue(script.contains("renderPluginDefinitionFields"));
            assertTrue(
                script.contains("普通同级子任务仍按顺序执行")
            );
            assertTrue(
                script.contains(
                    "多个 DIRECT 子任务会按定义顺序逐个进入"
                )
            );
            assertFalse(
                script.contains("子任务构成逻辑并行")
            );
            assertTrue(
                script.contains(
                    "node.task.type === TASK_TYPES.PARALLEL"
                )
            );
            assertTrue(script.contains("横向流程视图"));
            assertTrue(script.contains("主流程从左到右"));
            assertTrue(script.contains("PARALLEL · 并行"));
            assertTrue(script.contains("SUBFLOW · 子流程"));
            assertTrue(script.contains("class=\"flow-structure-board\""));
            assertTrue(script.contains("class=\"flow-layout-node\""));
            assertTrue(script.contains("data-layout-row="));
            assertTrue(script.contains("renderFlowBranchConnector"));
            assertTrue(script.contains("is-active"));
            assertTrue(script.contains("is-completed"));
            assertFalse(script.contains("class=\"semantic-group"));
            assertFalse(script.contains("从上到下执行"));
            assertFalse(script.contains("function layoutTasks"));
            String layoutScript = client.toBlocking().retrieve(
                HttpRequest.GET("/demo/flow-layout.js")
            );
            assertTrue(layoutScript.contains("buildTaskBlock"));
            assertTrue(layoutScript.contains("placeBlock"));
            assertTrue(layoutScript.contains("blockCollides"));
            assertTrue(layoutScript.contains("earlierChildExpanded"));
            assertTrue(layoutScript.contains("returnsToParentRow"));
            assertTrue(layoutScript.contains("taskBranchKind"));
            assertTrue(layoutScript.contains(
                "org.cses.flow.extensions.flow.Parallel"
            ));
            String styles = client.toBlocking().retrieve(
                HttpRequest.GET("/demo/demo.css")
            );
            assertTrue(styles.contains("@keyframes flow-node-pulse"));
            assertTrue(styles.contains(".flow-node.is-active"));
            assertTrue(styles.contains(".flow-node.is-completed"));
            assertTrue(script.contains("创建 Route 分支"));
            assertTrue(script.contains("填写流程基础信息"));
            assertTrue(script.contains("data-action=\"create-flow\""));
            assertTrue(
                script.contains("data-action=\"open-node-task-picker\"")
            );
            assertTrue(script.contains("data-field=\"task-route-output\""));
            assertTrue(script.contains("data-dependency-key"));
            assertTrue(script.contains("input-definition-fields"));
            assertTrue(script.contains("renderDataTypeOptions"));
            assertFalse(script.contains("<h2>流程结构</h2>"));
            assertFalse(script.contains("renderTaskStructureActions"));
            assertFalse(script.contains("resume-outputs"));

            JsonNode session = get(client, "/api/demo/session");
            assertEquals("demo-http-company", session.get("companyId").asText());
            assertEquals("demo-http-user", session.get("userId").asText());

            JsonNode dataTypes = get(client, "/api/demo/data-types");
            assertEquals(9, dataTypes.size());
            JsonNode integerType = null;
            for (JsonNode dataType : dataTypes) {
                if ("INTEGER".equals(dataType.get("code").asText())) {
                    integerType = dataType;
                    break;
                }
            }
            assertNotNull(integerType);
            assertEquals(
                "IntegerInput",
                integerType.get("inputClass").asText()
            );
            assertTrue(integerType.get("fields").toString().contains("min"));
            assertTrue(integerType.get("fields").toString().contains("max"));

            JsonNode created = post(
                client,
                "/api/demo/flows",
                Map.of("raw", automaticFlow("demo-http-flow"))
            );
            String flowId = created.get("id").asText();
            assertEquals(0L, created.get("lockVersion").asLong());
            assertFalse(created.has("deployedFlow"));

            JsonNode listed = get(client, "/api/demo/flows");
            assertEquals(1, listed.size());
            assertEquals(flowId, listed.get(0).get("id").asText());

            JsonNode updated = put(
                client,
                "/api/demo/flows/" + flowId,
                Map.of(
                    "raw",
                    automaticFlow("demo-http-flow"),
                    "expectedLockVersion",
                    0
                )
            );
            assertEquals(1L, updated.get("lockVersion").asLong());

            JsonNode deployed = post(
                client,
                "/api/demo/flows/" + flowId + "/deploy",
                Map.of()
            );
            assertEquals(
                1L,
                deployed.get("deployedFlow").get("reversion").asLong()
            );
            assertEquals(
                2,
                deployed.get("deployedFlow").get("tasks").size()
            );
            JsonNode deployedInput = deployed.get("deployedFlow")
                .get("inputs").get(0);
            assertEquals("INTEGER", deployedInput.get("type").asText());
            assertEquals(
                "尝试次数",
                deployedInput.get("displayName").asText()
            );
            assertEquals(
                1,
                deployedInput.get("constraints").get("min").asInt()
            );

            JsonNode started = post(
                client,
                "/api/demo/flows/" + flowId + "/executions",
                Map.of()
            );
            String executionId = started.get("id").asText();
            assertEquals("SUCCESS", started.get("state").asText());
            assertEquals(flowId, started.get("flowId").asText());
            assertEquals(2, started.get("taskRuns").size());

            JsonNode execution = get(
                client,
                "/api/demo/executions/" + executionId
            );
            assertEquals("SUCCESS", execution.get("state").asText());

            JsonNode reversion = get(
                client,
                "/api/demo/flows/" + flowId + "/reversions/1"
            );
            assertEquals("demo-http-flow", reversion.get("key").asText());

            HttpResponse<?> deleted = client.toBlocking().exchange(
                HttpRequest.DELETE("/api/demo/flows/" + flowId)
            );
            assertEquals(HttpStatus.NO_CONTENT, deleted.getStatus());
            assertEquals(0, get(client, "/api/demo/flows").size());
        }
    }

    @Test
    void ordinaryNestedTasksStaySerialUntilPriorSubtreeCompletes()
        throws IOException {

        try (EmbeddedServer server = startServer();
             HttpClient client = HttpClient.create(server.getURL())) {

            JsonNode created = post(
                client,
                "/api/demo/flows",
                Map.of("raw", serialNestedFlow())
            );
            String flowId = created.get("id").asText();
            JsonNode deployed = post(
                client,
                "/api/demo/flows/" + flowId + "/deploy",
                Map.of()
            );

            JsonNode waiting = post(
                client,
                "/api/demo/flows/" + flowId + "/executions",
                Map.of()
            );

            assertEquals("PAUSED", waiting.get("state").asText());
            assertEquals(3, waiting.get("taskRuns").size());
            String finishId = findTaskId(
                deployed.get("deployedFlow").get("tasks"),
                "serial-finish"
            );
            assertFalse(hasTaskRun(waiting, finishId));

            JsonNode approvalRun = waiting.get("taskRuns").get(1);
            JsonNode completed = post(
                client,
                "/api/demo/executions/"
                    + waiting.get("id").asText()
                    + "/task-runs/"
                    + approvalRun.get("id").asText()
                    + "/resume",
                Map.of(
                    "outputs",
                    Map.of(
                        "decision",
                        "APPROVED",
                        "comment",
                        "serial regression"
                    )
                )
            );

            assertEquals("SUCCESS", completed.get("state").asText());
            assertEquals(5, completed.get("taskRuns").size());
            assertTrue(hasTaskRun(completed, finishId));
        }
    }

    @Test
    void explicitParallelStartsEveryDirectBranch() throws IOException {
        try (EmbeddedServer server = startServer();
             HttpClient client = HttpClient.create(server.getURL())) {

            JsonNode created = post(
                client,
                "/api/demo/flows",
                Map.of("raw", explicitParallelFlow())
            );
            String flowId = created.get("id").asText();
            post(
                client,
                "/api/demo/flows/" + flowId + "/deploy",
                Map.of()
            );

            JsonNode completed = post(
                client,
                "/api/demo/flows/" + flowId + "/executions",
                Map.of()
            );

            assertEquals("SUCCESS", completed.get("state").asText());
            assertEquals(4, completed.get("taskRuns").size());
        }
    }

    @Test
    void resumesARealWaitingPauseExecutionOverHttp() throws IOException {
        try (EmbeddedServer server = startServer();
             HttpClient client = HttpClient.create(server.getURL())) {

            JsonNode created = post(
                client,
                "/api/demo/flows",
                Map.of("raw", pauseFlow())
            );
            String flowId = created.get("id").asText();
            JsonNode deployed = post(
                client,
                "/api/demo/flows/" + flowId + "/deploy",
                Map.of()
            );
            JsonNode confirm = deployed.get("deployedFlow")
                .get("tasks").get(1);
            assertEquals(
                "create-confirmation",
                confirm.get("pause").get("key").asText()
            );
            assertTrue(
                !confirm.has("tasks") || confirm.get("tasks").isEmpty()
            );
            assertEquals("decision", confirm.get("resume").get(0)
                .get("key").asText());
            assertEquals("STRING", confirm.get("outputs").get(0)
                .get("type").asText());

            JsonNode waiting = post(
                client,
                "/api/demo/flows/" + flowId + "/executions",
                Map.of()
            );
            assertEquals("PAUSED", waiting.get("state").asText());

            JsonNode waitingRun = null;
            for (JsonNode taskRun : waiting.get("taskRuns")) {
                if ("PAUSED".equals(taskRun.get("state").asText())) {
                    waitingRun = taskRun;
                    break;
                }
            }
            assertNotNull(waitingRun);

            JsonNode completed = post(
                client,
                "/api/demo/executions/"
                    + waiting.get("id").asText()
                    + "/task-runs/"
                    + waitingRun.get("id").asText()
                    + "/resume",
                Map.of(
                    "outputs",
                    Map.of(
                        "decision",
                        "APPROVED",
                        "comment",
                        "HTTP form approval"
                    )
                )
            );
            assertEquals("SUCCESS", completed.get("state").asText());
            assertEquals(4, completed.get("taskRuns").size());
            assertEquals(
                "APPROVED",
                completed.get("taskRuns").get(1)
                    .get("outputs").get("decision").asText()
            );
            assertEquals(
                "HTTP form approval",
                completed.get("taskRuns").get(1)
                    .get("outputs").get("comment").asText()
            );
        }
    }

    private static EmbeddedServer startServer() {
        Map<String, Object> properties = Map.ofEntries(
            Map.entry("micronaut.server.port", -1),
            Map.entry("flow.memory.enabled", true),
            Map.entry("flow.demo.enabled", true),
            Map.entry("flow.demo.session-binder.enabled", true),
            Map.entry("flow.demo.company-id", "demo-http-company"),
            Map.entry("flow.demo.user-id", "demo-http-user"),
            Map.entry("flow.demo.user-name", "Demo HTTP User"),
            Map.entry("datasources.default.enabled", false),
            Map.entry("flyway.datasources.default.enabled", false),
            Map.entry("micronaut.config-client.enabled", false),
            Map.entry("consul.client.registration.enabled", false),
            Map.entry("consul.client.config.enabled", false),
            Map.entry("consul.client.watch.service.enabled", false),
            Map.entry("grpc.server.enabled", false),
            Map.entry("grpc.server.health.enabled", false),
            Map.entry("thrift.server.enabled", false),
            Map.entry("pulsar.consumer.enabled", false),
            Map.entry(
                "micronaut.router.static-resources.flow-demo.mapping",
                "/demo/**"
            ),
            Map.entry(
                "micronaut.router.static-resources.flow-demo.paths",
                List.of("classpath:flow-demo")
            )
        );
        return ApplicationContext.run(EmbeddedServer.class, properties);
    }

    private static EmbeddedServer startPlatformManagedDemoServer() {
        Map<String, Object> properties = Map.ofEntries(
            Map.entry("micronaut.server.port", -1),
            Map.entry("flow.memory.enabled", true),
            Map.entry("flow.demo.platform-managed", true),
            Map.entry("datasources.default.enabled", false),
            Map.entry("flyway.datasources.default.enabled", false),
            Map.entry("micronaut.config-client.enabled", false),
            Map.entry("consul.client.registration.enabled", false),
            Map.entry("consul.client.config.enabled", false),
            Map.entry("consul.client.watch.service.enabled", false),
            Map.entry("grpc.server.enabled", false),
            Map.entry("grpc.server.health.enabled", false),
            Map.entry("thrift.server.enabled", false),
            Map.entry("pulsar.consumer.enabled", false)
        );
        return ApplicationContext.run(
            EmbeddedServer.class,
            properties,
            "demo",
            "test"
        );
    }

    private static JsonNode get(HttpClient client, String path)
        throws IOException {

        return json(client.toBlocking().retrieve(HttpRequest.GET(path)));
    }

    private static JsonNode post(
        HttpClient client,
        String path,
        Map<String, ?> body
    ) throws IOException {
        return json(client.toBlocking().retrieve(
            HttpRequest.POST(path, body)
        ));
    }

    private static JsonNode put(
        HttpClient client,
        String path,
        Map<String, ?> body
    ) throws IOException {
        return json(client.toBlocking().retrieve(
            HttpRequest.PUT(path, body)
        ));
    }

    private static JsonNode json(String value) throws IOException {
        return OBJECT_MAPPER.readTree(value);
    }

    private static String automaticFlow(String key) {
        return """
            key: %s
            description: HTTP demo lifecycle
            inputs:
              - key: attempts
                type: INTEGER
                displayName: 尝试次数
                required: true
                defaultValue: 2
                min: 1
                max: 5
            tasks:
              - key: prepare
                type: org.cses.flow.extensions.tasks.AutomaticTask
              - key: finish
                type: org.cses.flow.extensions.tasks.AutomaticTask
                dependOn:
                  - prepare
            """.formatted(key);
    }

    private static String pauseFlow() {
        return """
            key: demo-http-pause
            description: HTTP demo pause resume
            tasks:
              - key: prepare
                type: org.cses.flow.extensions.tasks.AutomaticTask
              - key: confirm
                type: org.cses.flow.extensions.flow.Pause
                dependOn:
                  - prepare
                pause:
                  key: create-confirmation
                  type: org.cses.flow.extensions.tasks.AutomaticTask
                resume:
                  - key: decision
                    type: STRING
                  - key: comment
                    type: STRING
              - key: finish
                type: org.cses.flow.extensions.tasks.AutomaticTask
                dependOn:
                  - confirm
            """;
    }

    private static String serialNestedFlow() {
        return """
            key: demo-http-serial-nested
            description: Ordinary children must remain serial
            tasks:
              - key: start
                type: org.cses.flow.extensions.tasks.AutomaticTask
                tasks:
                  - key: approval
                    type: org.cses.flow.extensions.flow.Pause
                    pause:
                      key: create-approval
                      type: org.cses.flow.extensions.tasks.AutomaticTask
                    resume:
                      - key: decision
                        type: STRING
                      - key: comment
                        type: STRING
                  - key: approved
                    type: org.cses.flow.extensions.tasks.AutomaticTask
                  - key: serial-finish
                    type: org.cses.flow.extensions.tasks.AutomaticTask
            """;
    }

    private static String explicitParallelFlow() {
        return """
            key: demo-http-explicit-parallel
            description: Explicit parallel task
            tasks:
              - key: parallel
                type: org.cses.flow.extensions.flow.Parallel
                tasks:
                  - key: left
                    type: org.cses.flow.extensions.tasks.AutomaticTask
                  - key: right
                    type: org.cses.flow.extensions.tasks.AutomaticTask
              - key: finish
                type: org.cses.flow.extensions.tasks.AutomaticTask
            """;
    }

    private static boolean hasTaskRun(
        JsonNode execution,
        String taskId
    ) {
        for (JsonNode taskRun : execution.get("taskRuns")) {
            if (taskId.equals(taskRun.get("taskId").asText())) {
                return true;
            }
        }
        return false;
    }

    private static String findTaskId(JsonNode tasks, String key) {
        for (JsonNode task : tasks) {
            if (key.equals(task.get("key").asText())) {
                return task.get("id").asText();
            }
            JsonNode children = task.get("tasks");
            if (children != null) {
                String childId = findTaskId(children, key);
                if (childId != null) {
                    return childId;
                }
            }
        }
        return null;
    }

    private static JsonNode findTaskPlugin(
        JsonNode registeredPlugins,
        String type
    ) {
        for (JsonNode registeredPlugin : registeredPlugins) {
            for (JsonNode task : registeredPlugin.get("tasks")) {
                if (type.equals(task.get("type").asText())) {
                    return task;
                }
            }
        }
        return null;
    }
}
