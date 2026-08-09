package org.cses.flow.core.domains.flows;

import org.cses.flow.core.domains.ActorRef;
import io.micronaut.json.JsonMapper;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.plugins.TaskPluginTestSupport.Context;
import org.cses.flow.core.plugins.TestNotificationTask;
import org.cses.flow.extensions.tasks.AutomaticTask;
import org.cses.flow.extensions.flow.LoopUntil;
import org.cses.flow.extensions.flow.Pause;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonFactory;
import org.paas.json.JsonObject;

import java.util.List;
import java.util.Map;

import static org.cses.flow.core.plugins.TaskPluginTestSupport.builtInContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowMaterializationTest {

    private static final ActorRef ACTOR =
        ActorRef.create("user-1", "Flow Designer");
    private static final long DEPLOYED_AT = 1_785_312_000_000L;
    private static final Context CONTEXT = builtInContext(
        new TestNotificationTask()
    );

    @BeforeAll
    static void initializeJsonMapper() {
        JsonFactory.instance = JsonMapper.createDefault();
    }

    @Test
    void materializesACompleteReversionWithExplicitDataObjects() {
        Flow flow = deploy(
            "flow-1",
            Map.of(
                "key", "release-flow",
                "description", "first",
                "inputs", List.of(Map.of(
                    "key", "request",
                    "type", " string ",
                    "displayName", "请求",
                    "required", true
                )),
                "outputs", List.of(Map.of(
                    "key", "result",
                    "type", "STRING"
                )),
                "tasks", List.of(Map.of(
                    "key", "prepare",
                    "type", AutomaticTask.class.getName(),
                    "outputs", List.of(Map.of(
                        "key", "prepared",
                        "type", "BOOLEAN"
                    )),
                    "tasks", List.of(Map.of(
                        "key", "approval",
                        "type", Pause.class.getName(),
                        "route", "DIRECT",
                        "pause", Map.of(
                            "key", "create-approval",
                            "type", AutomaticTask.class.getName()
                        )
                    ))
                ))
            ),
            null
        );

        assertEquals(1L, flow.reversion());
        assertTrue(!flow.isDeleted());
        assertEquals(
            List.of(input("request", "请求", true)),
            flow.inputs()
        );
        assertEquals(
            List.of(Output.create("result", DataType.STRING)),
            flow.outputs()
        );
        Task parent = flow.tasks().getFirst();
        assertEquals("DIRECT", parent.route().source());
        assertEquals("approval", parent.tasks().getFirst().key());
    }

    @Test
    void materializesRouteAndLoopUntilFromTheSameExpressLanguage() {
        Flow flow = deploy(
            "flow-express",
            Map.of(
                "key", "express-flow",
                "tasks", List.of(
                    Map.of(
                        "key", "prepare",
                        "type", AutomaticTask.class.getName(),
                        "outputs", List.of(Map.of(
                            "key", "decision",
                            "type", "STRING"
                        )),
                        "tasks", List.of(Map.of(
                            "key", "approved",
                            "type", AutomaticTask.class.getName(),
                            "route",
                            "outputs.decision == \"APPROVED\""
                        ))
                    ),
                    Map.of(
                        "key", "poll",
                        "type", LoopUntil.class.getName(),
                        "condition",
                        "outputs.check.status == \"DONE\"",
                        "maxIterations", 3,
                        "tasks", List.of(Map.of(
                            "key", "check",
                            "type", AutomaticTask.class.getName(),
                            "outputs", List.of(Map.of(
                                "key", "status",
                                "type", "STRING"
                            ))
                        ))
                    )
                )
            ),
            null
        );

        assertEquals(
            "decision",
            flow.tasks().getFirst().tasks().getFirst()
                .route().referencedOutputKey().orElseThrow()
        );
        LoopUntil loop = assertInstanceOf(
            LoopUntil.class,
            flow.tasks().getLast()
        );
        assertEquals(
            List.of("check", "status"),
            loop.condition().outputPath()
        );
    }

    @Test
    void keepsTaskIdentityStableAcrossReversions() {
        Flow first = deploy(
            "flow-1",
            definition("first", "prepare"),
            null
        );
        Flow second = deploy(
            "flow-1",
            definition("second", "prepare", "finish"),
            first
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
    void rejectsSystemFieldsUnknownPropertiesAndLegacyShortTypes() {
        IllegalArgumentException systemField = assertThrows(
            IllegalArgumentException.class,
            () -> deploy(
                "flow-1",
                Map.of(
                    "key", "invalid",
                    "tasks", List.of(Map.of(
                        "id", "user-controlled",
                        "key", "prepare",
                        "type", AutomaticTask.class.getName()
                    ))
                ),
                null
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
                        "type", AutomaticTask.class.getName(),
                        "retryTimes", 2
                    ))
                ),
                null
            )
        );
        assertTrue(pluginField.getMessage().contains("retryTimes"));

        IllegalArgumentException legacyType = assertThrows(
            IllegalArgumentException.class,
            () -> deploy(
                "flow-1",
                Map.of(
                    "key", "invalid",
                    "tasks", List.of(Map.of(
                        "key", "prepare",
                        "type", "AUTO"
                    ))
                ),
                null
            )
        );
        assertTrue(legacyType.getMessage().contains(
            "No plugin registered for type: AUTO"
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
                        "type", AutomaticTask.class.getName(),
                        "tasks", List.of(Map.of(
                            "key", "child",
                            "type", AutomaticTask.class.getName(),
                            "route", "outputs.missing == \"yes\""
                        ))
                    ))
                ),
                null
            )
        );
        assertTrue(route.getMessage().contains(
            "references undeclared parent context"
        ));

        WorkflowException typedRoute = assertThrows(
            WorkflowException.class,
            () -> deploy(
                "flow-1",
                Map.of(
                    "key", "invalid-route-type",
                    "tasks", List.of(Map.of(
                        "key", "parent",
                        "type", AutomaticTask.class.getName(),
                        "outputs", List.of(Map.of(
                            "key", "approved",
                            "type", "BOOLEAN"
                        )),
                        "tasks", List.of(Map.of(
                            "key", "child",
                            "type", AutomaticTask.class.getName(),
                            "route", "outputs.approved == \"yes\""
                        ))
                    ))
                ),
                null
            )
        );
        assertTrue(typedRoute.getMessage().contains(
            "requires STRING parent context"
        ));

        WorkflowException dependency = assertThrows(
            WorkflowException.class,
            () -> deploy(
                "flow-1",
                Map.of(
                    "key", "invalid-dependency",
                    "tasks", List.of(Map.of(
                        "key", "task",
                        "type", AutomaticTask.class.getName(),
                        "dependOn", List.of("missing")
                    ))
                ),
                null
            )
        );
        assertTrue(dependency.getMessage().contains(
            "Task dependency does not exist"
        ));
    }

    @Test
    void bindsPluginSpecificFieldsDirectlyToTheConcreteTask() {
        Flow flow = deploy(
            "flow-plugin",
            Map.of(
                "key", "plugin-flow",
                "tasks", List.of(Map.of(
                    "key", "notify",
                    "type", TestNotificationTask.class.getCanonicalName(),
                    "channel", "operations"
                ))
            ),
            null
        );

        TestNotificationTask notification = assertInstanceOf(
            TestNotificationTask.class,
            flow.tasks().getFirst()
        );
        assertEquals("operations", notification.channel());
        assertEquals(
            "operations",
            CONTEXT.jacksonMapper().toMap(notification).get("channel")
        );
    }

    @Test
    void activelyValidatesPluginSpecificFieldsAfterBinding() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> deploy(
                "flow-plugin",
                Map.of(
                    "key", "plugin-flow",
                    "tasks", List.of(Map.of(
                        "key", "notify",
                        "type",
                        TestNotificationTask.class.getCanonicalName()
                    ))
                ),
                null
            )
        );

        assertTrue(exception.getMessage().contains("channel"));
    }

    @Test
    void deletedLatestReversionBlocksAnotherDeployment() {
        Flow first = deploy(
            "flow-1",
            definition("first", "prepare"),
            null
        );
        first.delete(ACTOR, DEPLOYED_AT + 10_000L);

        assertTrue(first.isDeleted());
        assertEquals(1L, first.reversion());
        assertThrows(
            WorkflowException.class,
            () -> deploy(
                "flow-1",
                definition("second", "prepare"),
                first
            )
        );
    }

    private static Flow deploy(
        String id,
        Map<String, ?> definition,
        Flow latest
    ) {
        return CONTEXT.deploy(
            "company-1",
            id,
            definition,
            latest,
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
                    "type", AutomaticTask.class.getName()
                ))
                .toList();
        return Map.of(
            "key", "release-flow",
            "description", description,
            "tasks", tasks
        );
    }

    private static Input<?> input(
        String key,
        String displayName,
        boolean required
    ) {
        return JsonObject.FromMap(
            Map.of(
                "key", key,
                "type", "STRING",
                "displayName", displayName,
                "required", required
            )
        ).asObject(Input.class);
    }
}
