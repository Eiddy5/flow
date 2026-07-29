package org.cses.flow.core.domains.flows;

import org.cses.flow.core.domains.tasks.RouteExpression;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.domains.tasks.TaskPlugin;
import org.cses.flow.core.domains.tasks.TaskTypeDispatcher;
import org.cses.flow.core.exceptions.shared.WorkflowException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.cses.flow.extensions.tasks.TaskPluginTestSupport.builtInDispatcher;
import static org.cses.flow.extensions.tasks.TaskPluginTestSupport.withPlugins;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowMaterializationTest {

    private static final ActorRef ACTOR =
        ActorRef.create("user-1", "Flow Designer");
    private static final long DEPLOYED_AT = 1_785_312_000_000L;

    private final TaskTypeDispatcher dispatcher = builtInDispatcher();

    @Test
    void materializesACompleteReversionWithExplicitDataObjects() {
        Flow flow = deploy(
            "flow-1",
            Map.of(
                "key", "release-flow",
                "description", "first",
                "inputs", List.of(Map.of(
                    "key", "request",
                    "type", "JSON"
                )),
                "outputs", List.of(Map.of(
                    "key", "result",
                    "type", "STRING"
                )),
                "tasks", List.of(Map.of(
                    "key", "prepare",
                    "type", "AUTO",
                    "outputs", List.of(Map.of(
                        "key", "prepared",
                        "type", "BOOLEAN"
                    )),
                    "tasks", List.of(Map.of(
                        "key", "approval",
                        "type", "PAUSE",
                        "route", "DIRECT"
                    ))
                ))
            ),
            null,
            dispatcher
        );

        assertEquals(1L, flow.reversion());
        assertEquals(FlowStatus.DEPLOYED, flow.status());
        assertEquals(
            List.of(Input.create("request", "JSON")),
            flow.inputs()
        );
        assertEquals(
            List.of(Output.create("result", "STRING")),
            flow.outputs()
        );
        Task parent = flow.tasks().getFirst();
        assertEquals("DIRECT", parent.route().source());
        assertEquals(
            parent.id(),
            parent.tasks().getFirst().parentId().orElseThrow()
        );
    }

    @Test
    void keepsTaskIdentityStableAcrossReversions() {
        Flow first = deploy(
            "flow-1",
            definition("first", "prepare"),
            null,
            dispatcher
        );
        Flow second = deploy(
            "flow-1",
            definition("second", "prepare", "finish"),
            first,
            dispatcher
        );

        assertEquals(2L, second.reversion());
        assertEquals(
            first.tasks().getFirst().id(),
            second.tasks().getFirst().id()
        );
        assertNotEquals(
            first.tasks().getFirst().id(),
            second.tasks().getLast().id()
        );
        assertEquals("first", first.description());
        assertEquals(List.of("prepare"), first.tasks().stream()
            .map(Task::key)
            .toList());
    }

    @Test
    void rejectsSystemFieldsAndUnknownBuiltInProperties() {
        IllegalArgumentException systemField = assertThrows(
            IllegalArgumentException.class,
            () -> deploy(
                "flow-1",
                Map.of(
                    "key", "invalid",
                    "tasks", List.of(Map.of(
                        "id", "user-controlled",
                        "key", "prepare",
                        "type", "AUTO"
                    ))
                ),
                null,
                dispatcher
            )
        );
        assertTrue(systemField.getMessage().contains(
            "must not declare system field id"
        ));

        IllegalArgumentException pluginField = assertThrows(
            IllegalArgumentException.class,
            () -> deploy(
                "flow-1",
                Map.of(
                    "key", "invalid",
                    "tasks", List.of(Map.of(
                        "key", "prepare",
                        "type", "AUTO",
                        "retryTimes", 2
                    ))
                ),
                null,
                dispatcher
            )
        );
        assertTrue(pluginField.getMessage().contains(
            "AUTO Task contains unsupported fields"
        ));
    }

    @Test
    void validatesRoutesAndDependenciesDuringMaterialization() {
        WorkflowException route = assertThrows(
            WorkflowException.class,
            () -> deploy(
                "flow-1",
                Map.of(
                    "key", "invalid-route",
                    "tasks", List.of(Map.of(
                        "key", "parent",
                        "type", "AUTO",
                        "tasks", List.of(Map.of(
                            "key", "child",
                            "type", "AUTO",
                            "route",
                            "outputs.missing == \"yes\""
                        ))
                    ))
                ),
                null,
                dispatcher
            )
        );
        assertTrue(route.getMessage().contains(
            "references undeclared parent output"
        ));

        WorkflowException dependency = assertThrows(
            WorkflowException.class,
            () -> deploy(
                "flow-1",
                Map.of(
                    "key", "invalid-dependency",
                    "tasks", List.of(Map.of(
                        "key", "task",
                        "type", "AUTO",
                        "dependOn", List.of("missing")
                    ))
                ),
                null,
                dispatcher
            )
        );
        assertTrue(dependency.getMessage().contains(
            "Task dependency does not exist"
        ));
    }

    @Test
    void delegatesPluginSpecificYamlAndPersistenceSnapshotToPlugin() {
        TaskTypeDispatcher pluginDispatcher = withPlugins(
            new NotificationTaskPlugin()
        );
        Flow flow = deploy(
            "flow-plugin",
            Map.of(
                "key", "plugin-flow",
                "tasks", List.of(Map.of(
                    "key", "notify",
                    "type", "notification",
                    "channel", "operations"
                ))
            ),
            null,
            pluginDispatcher
        );

        Task task = flow.tasks().getFirst();
        NotificationTask notification = assertInstanceOf(
            NotificationTask.class,
            task
        );
        assertEquals("operations", notification.channel());
        assertEquals(
            Map.of("channel", "operations"),
            pluginDispatcher.properties(task)
        );
    }

    @Test
    void closedLatestReversionBlocksAnotherDeployment() {
        Flow first = deploy(
            "flow-1",
            definition("first", "prepare"),
            null,
            dispatcher
        );
        first.close(ACTOR, DEPLOYED_AT + 10_000L);

        assertThrows(
            WorkflowException.class,
            () -> deploy(
                "flow-1",
                definition("second", "prepare"),
                first,
                dispatcher
            )
        );
    }

    private static Flow deploy(
        String id,
        Map<String, ?> definition,
        Flow latest,
        TaskTypeDispatcher dispatcher
    ) {
        return Flow.deploy(
            "company-1",
            id,
            definition,
            latest,
            dispatcher,
            ACTOR,
            DEPLOYED_AT
        );
    }

    private static Map<String, Object> definition(
        String description,
        String... taskKeys
    ) {
        List<Map<String, Object>> tasks =
            java.util.Arrays.stream(taskKeys)
                .map(key -> Map.<String, Object>of(
                    "key", key,
                    "type", "AUTO"
                ))
                .toList();
        return Map.of(
            "key", "release-flow",
            "description", description,
            "tasks", tasks
        );
    }

    private static final class NotificationTask extends Task {

        private static final String TYPE = "NOTIFICATION";
        private final String channel;

        private NotificationTask(
            String id,
            String parentId,
            String key,
            List<? extends Input> inputs,
            List<? extends Output> outputs,
            RouteExpression route,
            List<String> dependOn,
            String channel,
            List<? extends Task> tasks
        ) {
            super(
                id,
                parentId,
                key,
                TYPE,
                inputs,
                outputs,
                route,
                dependOn,
                tasks
            );
            if (channel == null || channel.isBlank()) {
                throw new IllegalArgumentException(
                    "Notification channel must not be blank"
                );
            }
            this.channel = channel.trim();
        }

        private String channel() {
            return channel;
        }

        @Override
        protected Object typeSpecificEqualityState() {
            return channel;
        }
    }

    private static final class NotificationTaskPlugin
        implements TaskPlugin {

        @Override
        public String type() {
            return NotificationTask.TYPE;
        }

        @Override
        public Task create(
            String id,
            String parentId,
            String key,
            List<Input> inputs,
            List<Output> outputs,
            RouteExpression route,
            List<String> dependOn,
            Map<String, ?> properties,
            List<? extends Task> tasks
        ) {
            return materialize(
                id,
                parentId,
                key,
                inputs,
                outputs,
                route,
                dependOn,
                properties,
                tasks
            );
        }

        @Override
        public Task rehydrate(
            String id,
            String parentId,
            String key,
            List<Input> inputs,
            List<Output> outputs,
            RouteExpression route,
            List<String> dependOn,
            Map<String, ?> properties,
            List<? extends Task> tasks
        ) {
            return materialize(
                id,
                parentId,
                key,
                inputs,
                outputs,
                route,
                dependOn,
                properties,
                tasks
            );
        }

        @Override
        public Map<String, Object> properties(Task task) {
            if (!(task instanceof NotificationTask notification)) {
                throw new IllegalArgumentException(
                    "NOTIFICATION plugin requires NotificationTask"
                );
            }
            return Map.of("channel", notification.channel());
        }

        private static Task materialize(
            String id,
            String parentId,
            String key,
            List<Input> inputs,
            List<Output> outputs,
            RouteExpression route,
            List<String> dependOn,
            Map<String, ?> properties,
            List<? extends Task> tasks
        ) {
            Map<String, Object> copy = new LinkedHashMap<>();
            properties.forEach(copy::put);
            Object channel = copy.remove("channel");
            if (!copy.isEmpty()) {
                throw new IllegalArgumentException(
                    "Unsupported NOTIFICATION fields: " + copy.keySet()
                );
            }
            return new NotificationTask(
                id,
                parentId,
                key,
                inputs,
                outputs,
                route,
                dependOn,
                channel instanceof String text ? text : null,
                tasks
            );
        }
    }
}
