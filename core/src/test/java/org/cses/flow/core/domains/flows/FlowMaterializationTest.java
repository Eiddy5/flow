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
import org.cses.flow.extensions.flow.Route;
import org.cses.flow.extensions.flow.Sequence;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonFactory;
import org.paas.json.JsonObject;
import org.paas.session.Session;
import org.paas.session.User;

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

    private static Session<User> session() {
        User user = new User();
        user.setId(ACTOR.id());
        user.setName(ACTOR.name().orElse(null));
        user.setCompanyId("company-1");

        Session<User> session = new Session<>();
        session.setCompanyId("company-1");
        session.setUser(user);
        return session;
    }

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
                    "key", "release-steps",
                    "type", Sequence.class.getName(),
                    "tasks", List.of(
                        Map.of(
                            "key", "prepare",
                            "type", AutomaticTask.class.getName(),
                            "outputs", List.of(Map.of(
                                "key", "prepared",
                                "type", "BOOLEAN"
                            ))
                        ),
                        Map.of(
                            "key", "approval",
                            "type", Pause.class.getName(),
                            "pause", Map.of(
                                "key", "create-approval",
                                "type", AutomaticTask.class.getName()
                            )
                        )
                    )
                ))
            ),
            null
        );

        assertEquals(1L, flow.reversion());
        assertTrue(!flow.deleted());
        assertEquals(
            List.of(input("request", "请求", true)),
            flow.inputs()
        );
        assertEquals(
            List.of(Output.create("result", DataType.STRING)),
            flow.outputs()
        );
        Task parent = flow.tasks().getFirst();
        assertEquals("release-flow", flow.key());
        assertEquals("release-steps", parent.key());
        assertEquals("approval", parent.definitionChildren().getLast().key());
    }

    @Test
    void generatesMissingFlowAndTaskKeysAndReusesFlowFallback() {
        Flow first = deploy(
            null,
            Map.of(
                "tasks", List.of(Map.of(
                    "type", AutomaticTask.class.getName()
                ))
            ),
            null
        );

        assertTrue(first.key() != null && !first.key().isBlank());
        assertTrue(first.tasks().getFirst().key() != null);
        assertTrue(!first.tasks().getFirst().key().isBlank());

        Flow second = deploy(
            "unused-fallback",
            Map.of(
                "tasks", List.of(Map.of(
                    "type", AutomaticTask.class.getName()
                ))
            ),
            first
        );

        assertEquals(first.key(), second.key());
        assertEquals(2L, second.reversion());
    }

    @Test
    void materializesRouteAndLoopUntilFromTheSameConditionLanguage() {
        Flow flow = deploy(
            "flow-route",
            Map.of(
                "key", "route-flow",
                "tasks", List.of(
                    Map.of(
                        "key", "decision-sequence",
                        "type", Sequence.class.getName(),
                        "tasks", List.of(
                            Map.of(
                                "key", "prepare",
                                "type", AutomaticTask.class.getName(),
                                "outputs", List.of(Map.of(
                                    "key", "decision",
                                    "type", "STRING"
                                ))
                            ),
                            Map.of(
                                "key", "approved-route",
                                "type", Route.class.getName(),
                                "route",
                                "{{ outputs.prepare.decision }} == APPROVED",
                                "tasks", List.of(Map.of(
                                    "key", "approved",
                                    "type", AutomaticTask.class.getName()
                                ))
                            )
                        )
                    ),
                    Map.of(
                        "key", "poll",
                        "type", LoopUntil.class.getName(),
                        "condition",
                        "{{ outputs.check.status }} == DONE",
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

        Route route = assertInstanceOf(
            Route.class,
            flow.tasks().getFirst().definitionChildren().getLast()
        );
        assertEquals(
            "{{ outputs.prepare.decision }} == APPROVED",
            route.route()
        );
        assertEquals(
            List.of("prepare", "decision"),
            route.condition().references().getFirst().path()
        );
        LoopUntil loop = assertInstanceOf(
            LoopUntil.class,
            flow.tasks().getLast()
        );
        assertEquals(
            List.of("check", "status"),
            loop.condition().references().getFirst().path()
        );
    }

    @Test
    void materializesFlowVariablesAndValidatesVariableRoutes() {
        Flow flow = deploy(
            "flow-variables",
            Map.of(
                "key", "variable-flow",
                "variables", Map.of(
                    "environment", "prod",
                    "retryLimit", 3
                ),
                "tasks", List.of(Map.of(
                    "key", "production-route",
                    "type", Route.class.getName(),
                    "route", "{{ variables.environment }} == prod",
                    "tasks", List.of(Map.of(
                        "key", "production-only",
                        "type", AutomaticTask.class.getName()
                    ))
                ))
            ),
            null
        );

        assertEquals(
            Map.of("environment", "prod", "retryLimit", 3),
            flow.variables()
        );
        assertEquals(
            "environment",
            assertInstanceOf(Route.class, flow.tasks().getFirst())
                .condition().references().getFirst().path().getFirst()
        );
    }

    @Test
    void rejectsRoutesThatReferenceAnUndeclaredFlowVariable() {
        WorkflowException exception = assertThrows(
            WorkflowException.class,
            () -> deploy(
                "flow-variables",
                Map.of(
                    "key", "invalid-variable-route",
                    "tasks", List.of(Map.of(
                        "key", "invalid-route",
                        "type", Route.class.getName(),
                        "route", "{{ variables.environment }} == prod",
                        "tasks", List.of(Map.of(
                            "key", "child",
                            "type", AutomaticTask.class.getName()
                        ))
                    ))
                ),
                null
            )
        );

        assertTrue(exception.getMessage().contains(
            "references undeclared Flow variable"
        ));
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
    void validatesRouteExpressionsDuringMaterialization() {
        WorkflowException route = assertThrows(
            WorkflowException.class,
            () -> deploy(
                "flow-1",
                Map.of(
                    "key", "invalid-route",
                    "tasks", List.of(Map.of(
                        "key", "sequence",
                        "type", Sequence.class.getName(),
                        "tasks", List.of(
                            Map.of(
                                "key", "parent",
                                "type", AutomaticTask.class.getName()
                            ),
                            Map.of(
                                "key", "route",
                                "type", Route.class.getName(),
                                "route",
                                "{{ outputs.parent.missing }} == yes"
                            )
                        )
                    ))
                ),
                null
            )
        );
        assertTrue(route.getMessage().contains(
            "references an undeclared output"
        ));

        WorkflowException typedRoute = assertThrows(
            WorkflowException.class,
            () -> deploy(
                "flow-1",
                Map.of(
                    "key", "invalid-route-type",
                    "tasks", List.of(Map.of(
                        "key", "sequence",
                        "type", Sequence.class.getName(),
                        "tasks", List.of(
                            Map.of(
                                "key", "parent",
                                "type", AutomaticTask.class.getName(),
                                "outputs", List.of(Map.of(
                                    "key", "approved",
                                    "type", "BOOLEAN"
                                ))
                            ),
                            Map.of(
                                "key", "route",
                                "type", Route.class.getName(),
                                "route",
                                "{{ outputs.parent.approved }} == yes"
                            )
                        )
                    ))
                ),
                null
            )
        );
        assertTrue(typedRoute.getMessage().contains(
            "is incompatible with output"
        ));

        IllegalArgumentException dependency = assertThrows(
            IllegalArgumentException.class,
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
            "dependOn"
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
        first.delete(session(), first.createdAt() + 10_000L);

        assertTrue(first.deleted());
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

    @Test
    void validatesAndNormalizesConfirmedFlowInputsForRoutes() {
        Flow flow = deploy(
            "flow-input-route",
            Map.of(
                "key", "input-route",
                "inputs", List.of(
                    Map.of(
                        "key", "amount",
                        "type", "DOUBLE",
                        "displayName", "Amount",
                        "required", true
                    ),
                    Map.of(
                        "key", "urgent",
                        "type", "BOOLEAN",
                        "displayName", "Urgent",
                        "required", false,
                        "defaultValue", false
                    )
                ),
                "tasks", List.of(Map.of(
                    "key", "high-value-route",
                    "type", Route.class.getName(),
                    "route", "{{ inputs.amount }} > 1000",
                    "tasks", List.of(Map.of(
                        "key", "high-value",
                        "type", AutomaticTask.class.getName()
                    ))
                ))
            ),
            null
        );

        assertEquals(
            Map.of("amount", 1200.0, "urgent", false),
            flow.normalizeInputs(Map.of("amount", 1200))
        );
        assertThrows(
            WorkflowException.class,
            () -> flow.normalizeInputs(Map.of())
        );
        assertThrows(
            WorkflowException.class,
            () -> flow.normalizeInputs(Map.of(
                "amount", 1200,
                "business-only", "hidden"
            ))
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
