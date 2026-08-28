package org.cses.flow.infrastructure.repositories.postgres;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowId;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.plugins.TaskPluginTestSupport.Context;
import org.cses.flow.core.plugins.TestNotificationTask;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.infrastructure.repositories.executions.ExecutionRepositoryImpl;
import org.cses.flow.infrastructure.repositories.flows.FlowRepositoryImpl;
import org.cses.flow.infrastructure.jooq.FlowJooqTestConfiguration;
import org.cses.flow.extensions.flow.Pause;
import org.cses.flow.extensions.flow.Route;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.paas.common.util.StringUtil;
import org.paas.json.JsonFactory;
import org.paas.json.JsonObject;
import org.paas.session.Session;
import org.paas.session.User;
import org.paas.session.RecordState;
import io.micronaut.json.JsonMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.cses.flow.core.plugins.TaskPluginTestSupport.builtInContext;
import static org.flow.gen.flow.Tables.EXECUTIONS;
import static org.flow.gen.flow.Tables.FLOW_TASKS;
import static org.flow.gen.flow.Tables.FLOWS;
import static org.flow.gen.flow.Tables.TASK_RUNS;

@EnabledIfEnvironmentVariable(
        named = "FLOW_POSTGRES_TEST_URL",
        matches = ".+"
)
final class PostgresRepositoryIntegrationTest {

    private final Context plugins = builtInContext(
            new TestNotificationTask()
    );
    private final FlowRepositoryImpl flowRepository =
            new FlowRepositoryImpl(plugins.jacksonMapper());
    private FlowRepositoryImpl draftRepository = flowRepository;
    private final ExecutionRepositoryImpl executionRepository =
            new ExecutionRepositoryImpl();
    private final String companyId = "repository-test-" + StringUtil.newId();

    @BeforeAll
    static void initializeJsonMapper() {
        JsonFactory.instance = JsonMapper.createDefault();
    }

    @AfterEach
    void removeTestTenant() {
        write(dsl -> {
            dsl.deleteFrom(TASK_RUNS)
                    .where(TASK_RUNS.EXECUTION_ID.in(
                            dsl.select(EXECUTIONS.ID)
                                    .from(EXECUTIONS)
                                    .where(EXECUTIONS.COMPANY_ID.eq(companyId))
                    ))
                    .execute();
            dsl.deleteFrom(EXECUTIONS)
                    .where(EXECUTIONS.COMPANY_ID.eq(companyId))
                    .execute();
            dsl.deleteFrom(FLOW_TASKS)
                    .where(FLOW_TASKS.COMPANY_ID.eq(companyId))
                    .execute();
            dsl.deleteFrom(FLOWS)
                    .where(FLOWS.COMPANY_ID.eq(companyId))
                    .execute();
            return null;
        });
    }

    @Test
    void persistsAndRehydratesCurrentCoreAggregates() {
        ActorRef actor = ActorRef.create(
                "repository-user",
                "Repository Test"
        );
        Session<User> actorSession = session(actor);
        Flow draft = draft(
                actorSession,
                "postgres-flow",
                "key: postgres-flow"
        );
        long createdAt = draft.createdAt();
        write(dsl -> {
            draftRepository.save(dsl, draft);
            return null;
        });
        draft.revise(
                "revised",
                Map.of(),
                List.of(),
                List.of(),
                "key: postgres-flow\ndescription: revised",
                actorSession,
                createdAt + 1_000L
        );
        write(dsl -> {
            draftRepository.save(dsl, draft);
            return null;
        });
        Flow restoredDraft = read(dsl ->
                draftRepository.findById(
                        dsl,
                        companyId,
                        draft.id()
                ).orElseThrow()
        );
        assertEquals(draft, restoredDraft);

        Flow first = plugins.deploy(
                companyId,
                draft.key(),
                definition("postgres-flow", "first", "prepare"),
                null,
                actor,
                createdAt + 2_000L
        );
        write(dsl -> {
            flowRepository.save(dsl, first);
            return null;
        });
        String stableTaskId = first.allTasks().stream()
                .filter(task -> task.key().equals("prepare"))
                .findFirst()
                .orElseThrow()
                .id();

        Flow second = plugins.deploy(
                companyId,
                draft.key(),
                definition("postgres-flow", "second", "prepare"),
                first,
                actor,
                createdAt + 3_000L
        );
        write(dsl -> {
            flowRepository.save(dsl, second);
            return null;
        });

        Flow restoredFlow = read(dsl -> flowRepository.findLatestByFlowId(
                dsl,
                FlowId.from(companyId, first.key())
        ).orElseThrow());
        assertFalse(restoredFlow.deleted());
        assertEquals(2, restoredFlow.reversion());
        assertEquals(
                stableTaskId,
                second.allTasks().stream()
                        .filter(task -> task.key().equals("prepare"))
                        .findFirst()
                        .orElseThrow()
                        .id()
        );
        assertEquals(
                stableTaskId,
                restoredFlow.allTasks().stream()
                        .filter(task -> task.key().equals("prepare"))
                        .findFirst()
                        .orElseThrow()
                        .id()
        );
        assertEquals(
                List.of(Output.create("approved", DataType.STRING)),
                restoredFlow.allTasks().stream()
                        .filter(task -> task.key().equals("prepare"))
                        .findFirst()
                        .orElseThrow()
                        .outputs()
        );
        Route approvalRoute = assertInstanceOf(
                Route.class,
                restoredFlow.allTasks().stream()
                        .filter(task -> task.key().equals("approval-route"))
                        .findFirst()
                        .orElseThrow()
        );
        assertEquals(
                "{{ outputs.prepare.approved }} == yes",
                approvalRoute.route()
        );
        assertEquals(
                Pause.class.getName(),
                approvalRoute.definitionChildren().getFirst().getType()
        );
        Flow restoredFirst = read(dsl -> flowRepository.findByFlowId(
                dsl,
                FlowId.from(companyId, first.key(), 1)
        ).orElseThrow());
        assertFalse(restoredFirst.deleted());

        Execution execution = write(dsl -> {
            Execution created = Execution.create(
                    null,
                    actorSession,
                    restoredFlow.key(),
                    2,
                    Map.of("amount", 1200)
            );
            created.start();
            executionRepository.save(dsl, created);
            TaskRun createdTaskRun = created.createTaskRun(
                    stableTaskId,
                    null,
                    Map.of("request", "A-1")
            );
            executionRepository.save(dsl, created);
            created.startTaskRun(createdTaskRun.id());
            executionRepository.save(dsl, created);
            return created;
        });
        Execution restoredExecution = read(dsl ->
                executionRepository.findById(
                        dsl,
                        companyId,
                        execution.id()
                ).orElseThrow()
        );
        assertEquals(
                Map.of("amount", 1200),
                restoredExecution.inputs()
        );
        assertEquals(
                Map.of("request", "A-1"),
                restoredExecution.taskRuns().getFirst().inputs()
        );
        assertEquals(
                execution.taskRuns().getFirst().id(),
                restoredExecution.taskRuns().getFirst().id()
        );
        assertEquals(execution.state(), restoredExecution.state());
        assertEquals(
                execution.taskRuns().getFirst().state(),
                restoredExecution.taskRuns().getFirst().state()
        );
        JsonObject storedExecutionState = read(dsl -> JsonObject.Parse(
                dsl.select(EXECUTIONS.STATE)
                        .from(EXECUTIONS)
                        .where(EXECUTIONS.COMPANY_ID.eq(companyId))
                        .and(EXECUTIONS.ID.eq(execution.id()))
                        .fetchOne(EXECUTIONS.STATE)
                        .data()
        ));
        assertEquals(
                execution.state().current().name(),
                storedExecutionState.getString("current")
        );
        assertEquals(
                execution.state().history().size(),
                storedExecutionState.getObjects("history").size()
        );
        JsonObject storedTaskRunState = read(dsl -> JsonObject.Parse(
                dsl.select(TASK_RUNS.STATE)
                        .from(TASK_RUNS)
                        .where(TASK_RUNS.EXECUTION_ID.eq(execution.id()))
                        .and(TASK_RUNS.ID.eq(
                                execution.taskRuns().getFirst().id()
                        ))
                        .fetchOne(TASK_RUNS.STATE)
                        .data()
        ));
        assertEquals(
                execution.taskRuns().getFirst().state().current().name(),
                storedTaskRunState.getString("current")
        );
        assertEquals(
                execution.taskRuns().getFirst().state().history().size(),
                storedTaskRunState.getObjects("history").size()
        );
        long executionCount = read(dsl ->
                executionRepository.count(dsl, companyId)
        );
        assertEquals(1L, executionCount);

        Execution staleFirst = read(dsl ->
                executionRepository.findById(
                        dsl,
                        companyId,
                        execution.id()
                ).orElseThrow()
        );
        Execution staleSecond = read(dsl ->
                executionRepository.findById(
                        dsl,
                        companyId,
                        execution.id()
                ).orElseThrow()
        );
        staleFirst.beginKilling();
        staleSecond.beginKilling();
        write(dsl -> {
            executionRepository.save(dsl, staleFirst);
            return null;
        });
        write(dsl -> {
            executionRepository.save(dsl, staleSecond);
            return null;
        });
        Execution restoredAfterStaleWrites = read(dsl ->
                executionRepository.findById(
                        dsl,
                        companyId,
                        execution.id()
                ).orElseThrow()
        );
        assertEquals(
                State.Type.KILLING,
                restoredAfterStaleWrites.state().current()
        );

    }

    @Test
    void persistsCompatibleAuditStatesAndScopesFlowLookupByTenant() {
        ActorRef actor = ActorRef.create(
                "audit-state-user",
                "Audit State Test"
        );
        Session<User> actorSession = session(actor);
        Flow draft = draft(
                actorSession,
                "audit-state-flow",
                "key: audit-state-flow"
        );
        long createdAt = draft.createdAt();
        write(dsl -> {
            draftRepository.save(dsl, draft);
            return null;
        });

        draft.withState(
                RecordState.Archive,
                actorSession,
                createdAt + 1_000L
        );
        write(dsl -> {
            draftRepository.save(dsl, draft);
            return null;
        });

        Flow restoredDraft = read(dsl ->
                draftRepository.findDraftByFlowId(
                        dsl,
                        FlowId.from(companyId, draft.key())
                ).orElseThrow()
        );
        assertEquals(RecordState.Archive, restoredDraft.status());

        Flow flow = plugins.deploy(
                companyId,
                draft.key(),
                definition("audit-state-flow", "audited", "prepare"),
                null,
                actor,
                createdAt + 2_000L
        );
        write(dsl -> {
            flowRepository.save(dsl, flow);
            return null;
        });
        flow.withState(
                RecordState.Close,
                actorSession,
                flow.createdAt() + 1_000L
        );
        write(dsl -> {
            flowRepository.save(dsl, flow);
            return null;
        });

        Flow restoredFlow = read(dsl -> flowRepository.findByFlowId(
                dsl,
                FlowId.from(companyId, flow.key(), flow.version())
        ).orElseThrow());
        assertEquals(RecordState.Close, restoredFlow.status());
        assertTrue(read(dsl -> flowRepository.findByFlowId(
                dsl,
                FlowId.from(companyId + "-another", flow.key(), flow.version())
        )).isEmpty());
    }

    @Test
    void enforcesCompanyFlowKeyUniquenessIncludingDeletedDrafts() {
        ActorRef actor = ActorRef.create(
                "unique-key-user",
                "Unique Key Test"
        );
        Session<User> actorSession = session(actor);
        Flow first = draft(
                actorSession,
                "unique-repository-flow",
                "key: unique-repository-flow"
        );
        write(dsl -> {
            draftRepository.save(dsl, first);
            return null;
        });

        Flow duplicate = draft(
                actorSession,
                first.key(),
                first.source()
        );
        assertThrows(
                WorkflowException.class,
                () -> write(dsl -> {
                    draftRepository.save(dsl, duplicate);
                    return null;
                })
        );

        first.delete(actorSession, first.createdAt() + 2_000L);
        write(dsl -> {
            draftRepository.save(dsl, first);
            return null;
        });

        Flow replacement = draft(
                actorSession,
                first.key(),
                first.source()
        );
        assertThrows(
                WorkflowException.class,
                () -> write(dsl -> {
                    draftRepository.save(dsl, replacement);
                    return null;
                })
        );
    }

    @Test
    void roundTripsAPluginSpecificFieldWithoutACompanionCodec() {
        ActorRef actor = ActorRef.create(
                "plugin-user",
                "Plugin Repository Test"
        );
        String flowKey = "plugin-flow-" + StringUtil.newId();
        Flow flow = plugins.deploy(
                companyId,
                flowKey,
                Map.of(
                        "key", "plugin-flow",
                        "variables", Map.of(
                                "environment", "prod",
                                "retryLimit", 3
                        ),
                        "tasks", List.of(Map.of(
                                "key", "notify",
                                "type", TestNotificationTask.class.getCanonicalName(),
                                "channel", "operations"
                        ))
                ),
                null,
                actor,
                1_785_312_000_000L
        );

        write(dsl -> {
            flowRepository.save(dsl, flow);
            return null;
        });

        Flow restored = read(dsl -> flowRepository.findByFlowId(
                dsl,
                FlowId.from(companyId, flow.key(), 1L)
        ).orElseThrow());
        assertEquals(
                Map.of("environment", "prod", "retryLimit", 3),
                restored.variables()
        );
        TestNotificationTask task = assertInstanceOf(
                TestNotificationTask.class,
                restored.tasks().getFirst()
        );
        assertEquals("operations", task.channel());
        assertEquals(
                TestNotificationTask.class.getCanonicalName(),
                task.getType()
        );
        assertEquals(
                "operations",
                read(dsl -> JsonObject.Parse(
                        dsl.select(FLOW_TASKS.PROPERTIES)
                                .from(FLOW_TASKS)
                                .where(FLOW_TASKS.COMPANY_ID.eq(companyId))
                                .and(FLOW_TASKS.FLOW_KEY.eq(flow.key()))
                                .and(FLOW_TASKS.FLOW_VERSION.eq(flow.reversion()))
                                .fetchOne(FLOW_TASKS.PROPERTIES)
                                .data()
                ).getString("channel"))
        );
    }

    @Test
    void schemaUsesAggregateAndPluginTypes() {
        var columns = DSL.table(DSL.name(
                "information_schema",
                "columns"
        ));
        var tableSchema = DSL.field(
                DSL.name("table_schema"),
                String.class
        );
        var tableName = DSL.field(
                DSL.name("table_name"),
                String.class
        );
        var columnName = DSL.field(
                DSL.name("column_name"),
                String.class
        );
        var dataType = DSL.field(
                DSL.name("data_type"),
                String.class
        );

        int draftColumnCount = read(dsl -> dsl.fetchCount(
                columns,
                tableSchema.eq("public")
                        .and(tableName.eq("flows"))
                        .and(columnName.eq("draft"))
        ));
        int auditStatusColumnCount = read(dsl -> dsl.fetchCount(
                columns,
                tableSchema.eq("public")
                        .and(tableName.eq("flows"))
                        .and(columnName.eq("status"))
                        .and(dataType.eq("character varying"))
        ));
        int legacyDeletedColumnCount = read(dsl -> dsl.fetchCount(
                columns,
                tableSchema.eq("public")
                        .and(tableName.eq("flows"))
                        .and(columnName.eq("deleted"))
        ));
        int splitStateColumnCount = read(dsl -> dsl.fetchCount(
                columns,
                tableSchema.eq("public")
                        .and(tableName.in("executions", "task_runs"))
                        .and(columnName.in("status", "state_history"))
        ));
        int stateJsonbColumnCount = read(dsl -> dsl.fetchCount(
                columns,
                tableSchema.eq("public")
                        .and(tableName.in("executions", "task_runs"))
                        .and(columnName.eq("state"))
                        .and(dataType.eq("jsonb"))
        ));
        int flowVersionBindingColumnCount = read(dsl -> dsl.fetchCount(
                columns,
                tableSchema.eq("public")
                        .and(tableName.in("flow_tasks", "executions"))
                        .and(columnName.in("flow_key", "flow_version"))
        ));
        int legacyFlowBindingColumnCount = read(dsl -> dsl.fetchCount(
                columns,
                tableSchema.eq("public")
                        .and(tableName.in("flow_tasks", "executions"))
                        .and(columnName.in("flow_id", "flow_reversion"))
        ));
        int executionAdapterAuditColumnCount = read(dsl -> dsl.fetchCount(
                columns,
                tableSchema.eq("public")
                        .and(tableName.eq("executions"))
                        .and(columnName.in(
                                "updater",
                                "deleter",
                                "updated_at",
                                "deleted_at"
                        ))
        ));
        int taskRunNonDomainTimeColumnCount = read(dsl -> dsl.fetchCount(
                columns,
                tableSchema.eq("public")
                        .and(tableName.eq("task_runs"))
                        .and(columnName.in(
                                "start_at",
                                "end_at",
                                "created_at",
                                "updated_at",
                                "deleted_at"
                        ))
        ));
        int flowDerivedAuditColumnCount = read(dsl -> dsl.fetchCount(
                columns,
                tableSchema.eq("public")
                        .and(tableName.eq("flows"))
                        .and(columnName.in(
                                "creator_id",
                                "updater_id",
                                "deleter_id"
                        ))
        ));

        assertEquals(1, draftColumnCount);
        assertEquals(1, auditStatusColumnCount);
        assertEquals(0, legacyDeletedColumnCount);
        assertEquals(0, splitStateColumnCount);
        assertEquals(2, stateJsonbColumnCount);
        assertEquals(4, flowVersionBindingColumnCount);
        assertEquals(0, legacyFlowBindingColumnCount);
        assertEquals(0, executionAdapterAuditColumnCount);
        assertEquals(0, taskRunNonDomainTimeColumnCount);
        assertEquals(0, flowDerivedAuditColumnCount);
        assertEquals(
                "text",
                read(dsl -> dsl.select(dataType)
                        .from(columns)
                        .where(tableSchema.eq("public"))
                        .and(tableName.eq("flow_tasks"))
                        .and(columnName.eq("type"))
                        .fetchOne(dataType))
        );
    }

    @Test
    void persistsDeletionFactsWithoutFallingBackFromDeletedLatest() {
        ActorRef actor = ActorRef.create(
                "lifecycle-user",
                "Lifecycle Test"
        );
        Session<User> actorSession = session(actor);
        Flow draft = draft(
                actorSession,
                "lifecycle-flow",
                "key: lifecycle-flow"
        );
        long createdAt = draft.createdAt();
        write(dsl -> {
            draftRepository.save(dsl, draft);
            return null;
        });

        Flow first = plugins.deploy(
                companyId,
                draft.key(),
                definition("lifecycle-flow", "first", "prepare"),
                null,
                actor,
                createdAt + 1_000L
        );
        write(dsl -> {
            flowRepository.save(dsl, first);
            return null;
        });
        Flow second = plugins.deploy(
                companyId,
                draft.key(),
                definition("lifecycle-flow", "second", "prepare"),
                first,
                actor,
                createdAt + 2_000L
        );
        write(dsl -> {
            flowRepository.save(dsl, second);
            return null;
        });

        long deletedAt = Math.max(draft.updatedAt(), second.updatedAt())
                + 3_000L;
        draft.delete(actorSession, deletedAt);
        second.delete(actorSession, deletedAt);
        write(dsl -> {
            draftRepository.save(dsl, draft);
            flowRepository.save(dsl, second);
            return null;
        });

        Flow deletedDraft = read(dsl ->
                draftRepository.findById(
                        dsl,
                        companyId,
                        draft.id()
                ).orElseThrow()
        );
        assertTrue(deletedDraft.deleted());
        assertTrue(read(dsl -> draftRepository.findById(
                dsl,
                companyId,
                draft.id()
        )).orElseThrow().deleted());
        Flow latest = read(dsl -> flowRepository.findLatestByFlowId(
                dsl,
                FlowId.from(companyId, second.key())
        ).orElseThrow());
        assertEquals(2L, latest.reversion());
        assertTrue(latest.deleted());

        Flow historical = read(dsl -> flowRepository.findByFlowId(
                dsl,
                FlowId.from(companyId, second.key(), 1L)
        ).orElseThrow());
        assertFalse(historical.deleted());
        assertEquals(
                "Delete",
                read(dsl -> dsl.select(FLOWS.STATUS)
                        .from(FLOWS)
                        .where(FLOWS.COMPANY_ID.eq(companyId))
                        .and(FLOWS.ID.eq(draft.id()))
                        .fetchOne(FLOWS.STATUS))
        );
    }

    private static Map<String, Object> definition(
            String flowKey,
            String description,
            String taskKey
    ) {
        return Map.of(
                "key", flowKey,
                "description", description,
                "tasks", List.of(Map.of(
                        "key", "pipeline",
                        "type", org.cses.flow.extensions.flow.Sequence.class.getName(),
                        "tasks", List.of(
                                Map.of(
                                        "key", taskKey,
                                        "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName(),
                                        "inputs", List.of(Map.of(
                                                "key", "request",
                                                "type", "STRING",
                                                "displayName", "Request",
                                                "required", false
                                        )),
                                        "outputs", List.of(Map.of(
                                                "key", "approved",
                                                "type", "STRING"
                                        ))
                                ),
                                Map.of(
                                        "key", "approval-route",
                                        "type", Route.class.getName(),
                                        "route", "{{ outputs.%s.approved }} == yes".formatted(taskKey),
                                        "tasks", List.of(Map.of(
                                                "key", "approval",
                                                "type", Pause.class.getName(),
                                                "pause", Map.of(
                                                        "key", "create-approval",
                                                        "type", org.cses.flow.extensions.tasks.AutomaticTask.class.getName()
                                                ),
                                                "resume", List.of(Map.of(
                                                        "key", "approved",
                                                        "type", "STRING"
                                                ))
                                        ))
                                )
                        )
                ))
        );
    }

    private static Flow draft(
            Session<? extends User> session,
            String key,
            String source
    ) {
        return Flow.create(
                session,
                key,
                "",
                Map.of(),
                List.of(),
                List.of(),
                source
        );
    }

    private <T> T read(Function<DSLContext, T> operation) {
        return database(false, operation);
    }

    private <T> T write(Function<DSLContext, T> operation) {
        return database(true, operation);
    }

    private <T> T database(
            boolean write,
            Function<DSLContext, T> operation
    ) {
        String url = System.getenv("FLOW_POSTGRES_TEST_URL");
        String user = System.getenv().getOrDefault(
                "FLOW_POSTGRES_TEST_USER",
                "flow"
        );
        String password = System.getenv().getOrDefault(
                "FLOW_POSTGRES_TEST_PASSWORD",
                "flow"
        );
        try (Connection connection = DriverManager.getConnection(
                url,
                user,
                password
        )) {
            connection.setAutoCommit(false);
            DSLContext dsl = FlowJooqTestConfiguration.configure(
                    DSL.using(connection, SQLDialect.POSTGRES)
            );
            dsl.configuration().data(Session.class, session());
            T result = operation.apply(dsl);
            if (write) {
                connection.commit();
            } else {
                connection.rollback();
            }
            return result;
        } catch (Exception exception) {
            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(
                    "PostgreSQL repository integration failed",
                    exception
            );
        }
    }

    private Session<User> session() {
        return session(ActorRef.create(
                "repository-user",
                "Repository Test"
        ));
    }

    private Session<User> session(ActorRef actor) {
        User user = new User();
        user.setId(actor.id());
        user.setName(actor.name().orElse(null));
        user.setCompanyId(companyId);

        Session<User> session = new Session<>();
        session.setId("repository-test-session");
        session.setCompanyId(companyId);
        session.setUser(user);
        return session;
    }
}
