package org.cses.flow.infrastructure.repositories.postgres;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowId;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.plugins.TaskPluginTestSupport.Context;
import org.cses.flow.core.plugins.TestNotificationTask;
import org.cses.flow.infrastructure.repositories.executions.ExecutionRepositoryImpl;
import org.cses.flow.infrastructure.repositories.flows.FlowRepositoryImpl;
import org.cses.flow.infrastructure.jooq.FlowJooqTestConfiguration;
import org.cses.flow.infrastructure.jooq.PostgresJooqTestAdapter;
import org.cses.flow.extensions.flow.Pause;
import org.cses.flow.extensions.flow.Route;
import org.cses.flow.infrastructure.jooq.FlowDatabase;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataChangedException;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.cses.flow.core.plugins.TaskPluginTestSupport.builtInContext;
import static org.flow.gen.flow.Tables.EXECUTIONS;
import static org.flow.gen.flow.Tables.FLOW_TASKS;
import static org.flow.gen.flow.Tables.FLOWS;
import static org.flow.gen.flow.Tables.TASK_RUNS;

@EnabledIfEnvironmentVariable(
        named = "FLOW_POSTGRES_TEST_URL",
        matches = ".+"
)
class PostgresRepositoryIntegrationTest {

    private Context plugins = builtInContext(
            new TestNotificationTask()
    );
    private FlowRepositoryImpl flowRepository =
            new FlowRepositoryImpl();
    private FlowRepositoryImpl draftRepository = flowRepository;
    private ExecutionRepositoryImpl executionRepository =
            new ExecutionRepositoryImpl();
    private String companyId = "repository-test-" + StringUtil.newId();

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

    /**
     * Verifies Flow and Execution aggregate round trips across versioned Flow
     * rows and deployed Task snapshots. Two independent repositories then reject
     * a stale write and permit a complete domain update after reloading.
     */
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
        Flow savedDraft = save(draft);
        savedDraft.revise(
                "revised",
                Map.of(),
                List.of(),
                List.of(),
                "key: postgres-flow\ndescription: revised",
                actorSession,
                createdAt + 1_000L
        );
        Flow revisedDraft = save(savedDraft);
        Flow restoredDraft = read(dsl ->
                draftRepository.findById(
                        dsl,
                        companyId,
                        revisedDraft.id()
                ).orElseThrow()
        );
        assertEquals(revisedDraft, restoredDraft);
        assertEquals(1L, savedDraft.version());
        assertEquals(2L, revisedDraft.version());
        assertNotEquals(savedDraft.id(), revisedDraft.id());

        Flow first = plugins.deploy(
                companyId,
                revisedDraft.key(),
                definition("postgres-flow", "first", "prepare"),
                null,
                actor,
                createdAt + 2_000L
        );
        Flow savedFirst = save(first);
        String stableTaskId = savedFirst.allTasks().stream()
                .filter(task -> task.key().equals("prepare"))
                .findFirst()
                .orElseThrow()
                .id();

        Flow second = plugins.deploy(
                companyId,
                revisedDraft.key(),
                definition("postgres-flow", "second", "prepare"),
                savedFirst,
                actor,
                createdAt + 3_000L
        );
        Flow savedSecond = save(second);

        Flow restoredFlow = read(dsl -> flowRepository.findLatestByFlowId(
                dsl,
                FlowId.from(companyId, savedFirst.key())
        ).orElseThrow());
        assertFalse(restoredFlow.deleted());
        assertEquals(4, restoredFlow.reversion());
        assertEquals(
                stableTaskId,
                savedSecond.allTasks().stream()
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
                FlowId.from(companyId, savedFirst.key(), 3)
        ).orElseThrow());
        assertFalse(restoredFirst.deleted());

        Execution execution = write(dsl -> {
            Execution created = Execution.create(
                    null,
                    actorSession,
                    restoredFlow.key(),
                    restoredFlow.version(),
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
            TaskRun skippedRouteRun = created.createTaskRun(
                    approvalRoute.id(),
                    null,
                    Map.of("decision", "not-selected")
            );
            created.startTaskRun(skippedRouteRun.id());
            created.skipTaskRun(skippedRouteRun.id());
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
        TaskRun restoredSkippedRouteRun = restoredExecution.taskRuns()
                .getLast();
        assertEquals(
                State.Type.SKIPPED,
                restoredSkippedRouteRun.state().current()
        );
        assertEquals(
                List.of(
                        State.Type.CREATED,
                        State.Type.RUNNING,
                        State.Type.SKIPPED
                ),
                restoredSkippedRouteRun.state().history().stream()
                        .map(State.History::state)
                        .toList()
        );
        assertTrue(restoredSkippedRouteRun.outputs().isEmpty());
        assertTrue(restoredSkippedRouteRun.error().isEmpty());
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
        JsonObject storedSkippedRouteState = read(dsl -> JsonObject.Parse(
                dsl.select(TASK_RUNS.STATE)
                        .from(TASK_RUNS)
                        .where(TASK_RUNS.EXECUTION_ID.eq(execution.id()))
                        .and(TASK_RUNS.ID.eq(
                                execution.taskRuns().getLast().id()
                        ))
                        .fetchOne(TASK_RUNS.STATE)
                        .data()
        ));
        assertEquals(
                State.Type.SKIPPED.name(),
                storedSkippedRouteState.getString("current")
        );
        assertEquals(
                3,
                storedSkippedRouteState.getObjects("history").size()
        );
        long executionCount = read(dsl ->
                executionRepository.count(dsl, companyId)
        );
        assertEquals(1L, executionCount);

        DSLContext direct = PostgresJooqTestAdapter.fromEnvironment().createDSLContext();
        direct.configuration().data(Session.class, actorSession);
        FlowDatabase.execute(direct, scoped -> {
            ExecutionRepositoryImpl firstWriter = new ExecutionRepositoryImpl();
            ExecutionRepositoryImpl secondWriter = new ExecutionRepositoryImpl();
            Execution staleFirst = firstWriter.findById(scoped, companyId, execution.id()).orElseThrow();
            Execution staleSecond = secondWriter.findById(scoped, companyId, execution.id()).orElseThrow();
            staleFirst.beginKilling();
            staleFirst.killUnfinishedTaskRuns();
            staleSecond.succeedTaskRun(execution.taskRuns().getFirst().id(), Map.of("approved", "stale"));
            firstWriter.save(scoped, staleFirst);

            assertThrows(DataChangedException.class, () -> secondWriter.save(scoped, staleSecond));
            Execution retained = secondWriter.findById(scoped, companyId, execution.id()).orElseThrow();
            assertEquals(staleFirst.state(), retained.state());
            assertEquals(staleFirst.taskRuns().stream().map(TaskRun::id).toList(),
                    retained.taskRuns().stream().map(TaskRun::id).toList());
            assertEquals(staleFirst.taskRuns().stream().map(TaskRun::state).toList(),
                    retained.taskRuns().stream().map(TaskRun::state).toList());
            assertEquals(staleFirst.taskRuns().stream().map(TaskRun::inputs).toList(),
                    retained.taskRuns().stream().map(TaskRun::inputs).toList());
            assertEquals(staleFirst.taskRuns().stream().map(TaskRun::outputs).toList(),
                    retained.taskRuns().stream().map(TaskRun::outputs).toList());

            retained.finishKilling();
            secondWriter.save(scoped, retained);
            Execution completed = firstWriter.findById(scoped, companyId, execution.id()).orElseThrow();
            assertEquals(State.Type.KILLED, completed.state().current());
            assertEquals(retained.taskRuns().stream().map(TaskRun::id).toList(),
                    completed.taskRuns().stream().map(TaskRun::id).toList());
            assertEquals(retained.taskRuns().stream().map(TaskRun::state).toList(),
                    completed.taskRuns().stream().map(TaskRun::state).toList());
            assertTrue(completed.unfinishedTaskRuns().isEmpty());
            return null;
        });
    }

    /**
     * Saves Flow and Execution aggregates through an auto-commit DSL and forces
     * a child storage failure. A failed creation must leave no rows; a failed update must retain
     * the prior parent state and child identity, state and input.
     */
    @Test
    void childSaveFailureDoesNotPersistHalfAnAggregateWithoutCallerTransaction() {
        DSLContext direct = PostgresJooqTestAdapter.fromEnvironment().createDSLContext();
        FlowDatabase.execute(direct, dsl -> {
            dsl.configuration().data(Session.class, session());
            Flow rejectedFlow = plugins.deploy(
                    companyId,
                    "atomic-flow",
                    Map.of(
                            "key", "atomic-flow",
                            "tasks", List.of(Map.of(
                                    "key", "x".repeat(FLOW_TASKS.KEY.getDataType().length() + 1),
                                    "type", org.cses.flow.extensions.log.Log.class.getName(),
                                    "message", "test step"
                            ))
                    ),
                    null,
                    ActorRef.create("repository-user", "Repository Test"),
                    1_785_312_000_000L
            );

            assertThrows(WorkflowException.class, () -> flowRepository.save(dsl, rejectedFlow));
            assertEquals(0, dsl.fetchCount(FLOWS,
                    FLOWS.COMPANY_ID.eq(companyId).and(FLOWS.KEY.eq(rejectedFlow.key()))));
            assertEquals(0, dsl.fetchCount(FLOW_TASKS,
                    FLOW_TASKS.COMPANY_ID.eq(companyId).and(FLOW_TASKS.FLOW_KEY.eq(rejectedFlow.key()))));

            String oversizedTaskId = "x".repeat(TASK_RUNS.TASK_ID.getDataType().length() + 1);
            Execution rejected = Execution.create(
                    null, session(), "atomic-create", 1L, Map.of("request", "new")
            );
            rejected.start();
            rejected.createTaskRun(StringUtil.newId(), null, Map.of("step", "valid"));
            rejected.createTaskRun(oversizedTaskId, null, Map.of());

            assertThrows(WorkflowException.class, () -> executionRepository.save(dsl, rejected));
            assertTrue(executionRepository.findById(dsl, companyId, rejected.id()).isEmpty());
            assertEquals(0, dsl.fetchCount(TASK_RUNS, TASK_RUNS.EXECUTION_ID.eq(rejected.id())));

            Execution original = Execution.create(
                    null, session(), "atomic-update", 1L, Map.of("request", "existing")
            );
            original.start();
            TaskRun originalRun = original.createTaskRun(
                    StringUtil.newId(), null, Map.of("step", "original")
            );
            original.startTaskRun(originalRun.id());
            executionRepository.save(dsl, original);
            Execution changed = executionRepository.findById(dsl, companyId, original.id())
                    .orElseThrow();
            changed.createTaskRun(oversizedTaskId, null, Map.of());
            changed.beginKilling();
            changed.killUnfinishedTaskRuns();
            changed.finishKilling();

            assertThrows(WorkflowException.class, () -> executionRepository.save(dsl, changed));
            Execution retained = executionRepository.findById(dsl, companyId, original.id())
                    .orElseThrow();
            assertEquals(original.state(), retained.state());
            assertEquals(original.inputs(), retained.inputs());
            assertEquals(List.of(originalRun.id()), retained.taskRuns().stream().map(TaskRun::id).toList());
            assertEquals(originalRun.state(), retained.requireTaskRun(originalRun.id()).state());
            assertEquals(originalRun.inputs(), retained.requireTaskRun(originalRun.id()).inputs());
            assertEquals(originalRun.outputs(), retained.requireTaskRun(originalRun.id()).outputs());
            assertEquals(1, dsl.fetchCount(TASK_RUNS, TASK_RUNS.EXECUTION_ID.eq(original.id())));
            assertEquals(0L, dsl.select(EXECUTIONS.LOCK).from(EXECUTIONS)
                    .where(EXECUTIONS.COMPANY_ID.eq(companyId)).and(EXECUTIONS.ID.eq(original.id()))
                    .fetchOne(EXECUTIONS.LOCK));
            return null;
        });
    }

    /**
     * Verifies that compatible audit states append as new versions and remain
     * scoped to their tenant.
     */
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
        Flow savedDraft = save(draft);

        savedDraft.withState(
                RecordState.Archive,
                actorSession,
                createdAt + 1_000L
        );
        Flow archivedDraft = save(savedDraft);

        Flow restoredDraft = read(dsl ->
                draftRepository.findDraftByFlowId(
                        dsl,
                        FlowId.from(companyId, archivedDraft.key())
                ).orElseThrow()
        );
        assertEquals(RecordState.Archive, restoredDraft.status());

        Flow flow = plugins.deploy(
                companyId,
                archivedDraft.key(),
                definition("audit-state-flow", "audited", "prepare"),
                null,
                actor,
                createdAt + 2_000L
        );
        Flow savedFlow = save(flow);
        savedFlow.withState(
                RecordState.Close,
                actorSession,
                savedFlow.createdAt() + 1_000L
        );
        Flow closedFlow = save(savedFlow);

        Flow restoredFlow = read(dsl -> flowRepository.findByFlowId(
                dsl,
                FlowId.from(
                        companyId,
                        closedFlow.key(),
                        closedFlow.version()
                )
        ).orElseThrow());
        assertEquals(RecordState.Close, restoredFlow.status());
        assertTrue(read(dsl -> flowRepository.findByFlowId(
                dsl,
                FlowId.from(
                        companyId + "-another",
                        closedFlow.key(),
                        closedFlow.version()
                )
        )).isEmpty());
    }

    /**
     * Verifies that every save appends a new row and that deleted versions
     * remain part of the tenant-and-key version sequence.
     */
    @Test
    void appendsVersionsIncludingDeletedDrafts() {
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
        Flow savedFirst = save(first);

        Flow duplicate = draft(
                actorSession,
                first.key(),
                first.source()
        );
        Flow savedSecond = save(duplicate);

        savedSecond.delete(
                actorSession,
                savedSecond.createdAt() + 2_000L
        );
        Flow deleted = save(savedSecond);

        Flow replacement = draft(
                actorSession,
                first.key(),
                first.source()
        );
        Flow savedReplacement = save(replacement);

        assertEquals(1L, savedFirst.version());
        assertEquals(2L, savedSecond.version());
        assertEquals(3L, deleted.version());
        assertEquals(4L, savedReplacement.version());
        int savedRowCount = read(dsl -> dsl.fetchCount(
                FLOWS,
                FLOWS.COMPANY_ID.eq(companyId)
                        .and(FLOWS.KEY.eq(first.key()))
        ));
        assertEquals(4, savedRowCount);
        assertEquals(
                savedReplacement.id(),
                read(dsl -> draftRepository.findDraftByFlowId(
                        dsl,
                        FlowId.from(companyId, first.key())
                ).orElseThrow()).id()
        );
        assertNotEquals(savedFirst.id(), savedSecond.id());
        assertNotEquals(savedSecond.id(), deleted.id());
        assertNotEquals(deleted.id(), savedReplacement.id());
    }

    /**
     * Verifies that plugin-specific Task properties use the Repository-
     * assigned deployed Flow version.
     */
    @Test
    void roundTripsAPluginSpecificFieldThroughTaskPropertiesCodec() {
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

        Flow savedFlow = save(flow);

        Flow restored = read(dsl -> flowRepository.findByFlowId(
                dsl,
                FlowId.from(companyId, savedFlow.key(), savedFlow.version())
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
                                .and(FLOW_TASKS.FLOW_KEY.eq(savedFlow.key()))
                                .and(FLOW_TASKS.FLOW_VERSION.eq(
                                        savedFlow.version()
                                ))
                                .fetchOne(FLOW_TASKS.PROPERTIES)
                                .data()
                ).getString("channel"))
        );
    }

    /**
     * Verifies the manually provisioned schema types, including the required
     * non-null Flow version column.
     */
    @Test
    void schemaUsesAggregateAndPluginTypes() {
        var columns = DSL.table(DSL.name(
                "information_schema",
                "columns"
        ));
        var indexes = DSL.table(DSL.name("pg_indexes"));
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
        var isNullable = DSL.field(
                DSL.name("is_nullable"),
                String.class
        );
        var schemaName = DSL.field(
                DSL.name("schemaname"),
                String.class
        );
        var indexTableName = DSL.field(
                DSL.name("tablename"),
                String.class
        );
        var indexName = DSL.field(
                DSL.name("indexname"),
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
        int executionAuditColumnCount = read(dsl -> dsl.fetchCount(
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
        int taskRunAuditColumnCount = read(dsl -> dsl.fetchCount(
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
        int requiredFlowVersionColumnCount = read(dsl -> dsl.fetchCount(
                columns,
                tableSchema.eq("public")
                        .and(tableName.eq("flows"))
                        .and(columnName.eq("version"))
                        .and(isNullable.eq("NO"))
        ));
        int flowVersionUniqueIndexCount = read(dsl -> dsl.fetchCount(
                indexes,
                schemaName.eq("public")
                        .and(indexTableName.eq("flows"))
                        .and(indexName.eq("uq_flows_key_version"))
        ));

        int lockColumnCount = read(dsl -> dsl.fetchCount(columns,
                tableSchema.eq("public").and(tableName.eq("executions"))
                        .and(columnName.eq("lock")).and(dataType.eq("bigint")).and(isNullable.eq("NO"))));
        int legacyLockColumnCount = read(dsl -> dsl.fetchCount(columns,
                tableSchema.eq("public").and(tableName.eq("executions"))
                        .and(columnName.eq("lock_version"))));

        assertEquals(1, draftColumnCount);
        assertEquals(1, auditStatusColumnCount);
        assertEquals(0, legacyDeletedColumnCount);
        assertEquals(0, splitStateColumnCount);
        assertEquals(2, stateJsonbColumnCount);
        assertEquals(4, flowVersionBindingColumnCount);
        assertEquals(0, legacyFlowBindingColumnCount);
        assertEquals(0, executionAuditColumnCount);
        assertEquals(0, taskRunAuditColumnCount);
        assertEquals(0, flowDerivedAuditColumnCount);
        assertEquals(1, requiredFlowVersionColumnCount);
        assertEquals(1, flowVersionUniqueIndexCount);
        assertEquals(1, lockColumnCount);
        assertEquals(0, legacyLockColumnCount);
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

    /**
     * Verifies that deletion appends new draft and deployed versions without
     * causing latest queries to fall back to older active rows.
     */
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
        Flow savedDraft = save(draft);

        Flow first = plugins.deploy(
                companyId,
                savedDraft.key(),
                definition("lifecycle-flow", "first", "prepare"),
                null,
                actor,
                createdAt + 1_000L
        );
        Flow savedFirst = save(first);
        Flow second = plugins.deploy(
                companyId,
                savedDraft.key(),
                definition("lifecycle-flow", "second", "prepare"),
                savedFirst,
                actor,
                createdAt + 2_000L
        );
        Flow savedSecond = save(second);

        long deletedAt = Math.max(
                savedDraft.updatedAt(),
                savedSecond.updatedAt()
        )
                + 3_000L;
        savedDraft.delete(actorSession, deletedAt);
        savedSecond.delete(actorSession, deletedAt);
        Flow deletedDraft = save(savedDraft);
        Flow deletedFlow = save(savedSecond);

        Flow restoredDeletedDraft = read(dsl ->
                draftRepository.findById(
                        dsl,
                        companyId,
                        deletedDraft.id()
                ).orElseThrow()
        );
        assertTrue(restoredDeletedDraft.deleted());
        assertTrue(read(dsl -> draftRepository.findById(
                dsl,
                companyId,
                deletedDraft.id()
        )).orElseThrow().deleted());
        Flow latest = read(dsl -> flowRepository.findLatestByFlowId(
                dsl,
                FlowId.from(companyId, deletedFlow.key())
        ).orElseThrow());
        assertEquals(5L, latest.reversion());
        assertTrue(latest.deleted());
        assertTrue(read(dsl -> draftRepository.findDraftByFlowId(
                dsl,
                FlowId.from(companyId, deletedDraft.key())
        )).isEmpty());
        assertTrue(read(dsl -> draftRepository.findDrafts(
                dsl,
                companyId
        )).isEmpty());

        Flow historical = read(dsl -> flowRepository.findByFlowId(
                dsl,
                FlowId.from(companyId, deletedFlow.key(), 2L)
        ).orElseThrow());
        assertFalse(historical.deleted());
        assertEquals(
                "Delete",
                read(dsl -> dsl.select(FLOWS.STATUS)
                        .from(FLOWS)
                        .where(FLOWS.COMPANY_ID.eq(companyId))
                        .and(FLOWS.ID.eq(deletedDraft.id()))
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
                                        "type", org.cses.flow.extensions.log.Log.class.getName(), "message", "test step",
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
                                                "onPause", Map.of(
                                                        "key", "create-approval",
                                                        "type", org.cses.flow.extensions.log.Log.class.getName(), "message", "test step"
                                                ),
                                                "onResume", List.of(Map.of(
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

    /**
     * Saves one Flow in a committed transaction and returns the persisted
     * snapshot produced by the Repository.
     *
     * @param flow non-null Flow state read without modification
     * @return persisted Flow with its assigned row id and version
     * @throws WorkflowException when the database rejects the append
     */
    private Flow save(Flow flow) {
        return write(dsl -> flowRepository.save(dsl, flow));
    }

    private <T> T read(Function<DSLContext, T> operation) {
        return database(false, operation);
    }

    private <T> T write(Function<DSLContext, T> operation) {
        return database(true, operation);
    }

    /**
     * 在真实事务完成后结束仓储会话，释放该次加载版本。
     *
     * @param <T> 操作结果类型
     * @param write 是否提交写入
     * @param operation 数据库操作
     * @return 操作结果
     */
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
            return FlowDatabase.execute(dsl, scoped -> {
                try {
                    T result = operation.apply(scoped);
                    if (write) {
                        connection.commit();
                    } else {
                        connection.rollback();
                    }
                    return result;
                } catch (Exception exception) {
                    try {
                        connection.rollback();
                    } catch (java.sql.SQLException rollback) {
                        exception.addSuppressed(rollback);
                    }
                    if (exception instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    throw new IllegalStateException("PostgreSQL transaction failed", exception);
                }
            });
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
