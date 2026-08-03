package org.cses.flow.infrastructure.repositories.postgres;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.externaltasks.ExternalTask;
import org.cses.flow.core.domains.externaltasks.ExternalTaskStatus;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowDraft;
import org.cses.flow.core.domains.flows.ActorRef;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.plugins.TaskTypeDispatcher;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.infrastructure.repositories.executions.postgres.ExecutionPostgresRepository;
import org.cses.flow.infrastructure.repositories.externaltasks.postgres.ExternalTaskPostgresRepository;
import org.cses.flow.infrastructure.repositories.flows.postgres.FlowPostgresRepository;
import org.cses.flow.infrastructure.repositories.flows.postgres.FlowDraftPostgresRepository;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.paas.common.util.StringUtil;
import org.paas.json.JsonFactory;
import org.paas.session.Session;
import org.paas.session.User;
import io.micronaut.json.JsonMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.cses.flow.core.plugins.TaskExtensionTestSupport.builtInDispatcher;
import static org.flow.gen.flow.Tables.EXECUTIONS;
import static org.flow.gen.flow.Tables.EXTERNAL_TASK;
import static org.flow.gen.flow.Tables.FLOW_DRAFTS;
import static org.flow.gen.flow.Tables.FLOW_TASKS;
import static org.flow.gen.flow.Tables.FLOWS;
import static org.flow.gen.flow.Tables.TASK_RUN;

@EnabledIfEnvironmentVariable(
    named = "FLOW_POSTGRES_TEST_URL",
    matches = ".+"
)
final class PostgresRepositoryIntegrationTest {

    private final TaskTypeDispatcher taskTypeDispatcher =
        builtInDispatcher();
    private final FlowPostgresRepository flowRepository =
        new FlowPostgresRepository(taskTypeDispatcher);
    private final FlowDraftPostgresRepository draftRepository =
        new FlowDraftPostgresRepository();
    private final ExecutionPostgresRepository executionRepository =
        new ExecutionPostgresRepository();
    private final ExternalTaskPostgresRepository externalTaskRepository =
        new ExternalTaskPostgresRepository();
    private final String companyId = "repository-test-" + StringUtil.newId();

    @BeforeAll
    static void initializeJsonMapper() {
        JsonFactory.instance = JsonMapper.createDefault();
    }

    @AfterEach
    void removeTestTenant() {
        write(dsl -> {
            dsl.deleteFrom(EXTERNAL_TASK)
                .where(EXTERNAL_TASK.COMPANY_ID.eq(companyId))
                .execute();
            dsl.deleteFrom(TASK_RUN)
                .where(TASK_RUN.EXECUTION_ID.in(
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
            dsl.deleteFrom(FLOW_DRAFTS)
                .where(FLOW_DRAFTS.COMPANY_ID.eq(companyId))
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
        long createdAt = 1_785_312_000_000L;
        FlowDraft draft = FlowDraft.create(
            companyId,
            "key: postgres-flow",
            actor,
            createdAt
        );
        write(dsl -> {
            draftRepository.save(dsl, draft);
            return null;
        });
        draft.revise(
            "key: postgres-flow\ndescription: revised",
            actor,
            createdAt + 1_000L
        );
        write(dsl -> {
            draftRepository.save(dsl, draft);
            return null;
        });
        FlowDraft restoredDraft = read(dsl ->
            draftRepository.findById(
                dsl,
                companyId,
                draft.id()
            ).orElseThrow()
        );
        assertEquals(draft, restoredDraft);

        Flow first = Flow.deploy(
            companyId,
            draft.id(),
            definition("postgres-flow", "first", "prepare"),
            null,
            taskTypeDispatcher,
            actor,
            createdAt + 2_000L
        );
        write(dsl -> {
            flowRepository.save(dsl, first);
            return null;
        });
        String stableTaskId = first.tasks().getFirst().id();

        Flow second = Flow.deploy(
            companyId,
            draft.id(),
            definition("postgres-flow", "second", "prepare"),
            first,
            taskTypeDispatcher,
            actor,
            createdAt + 3_000L
        );
        write(dsl -> {
            flowRepository.save(dsl, second);
            return null;
        });

        Flow restoredFlow = read(dsl -> flowRepository.findLatest(
            dsl,
            companyId,
            draft.id()
        ).orElseThrow());
        assertTrue(!restoredFlow.isDeleted());
        assertEquals(2, restoredFlow.reversion());
        assertEquals(
            stableTaskId,
            second.tasks().getFirst().id()
        );
        assertEquals(
            stableTaskId,
            restoredFlow.tasks().getFirst().id()
        );
        assertEquals(
            List.of(Output.create("approved", DataType.STRING)),
            restoredFlow.tasks().getFirst().outputs()
        );
        assertEquals(
            "PAUSE",
            restoredFlow.tasks().getFirst().tasks().getFirst().type()
        );
        Flow restoredFirst = read(dsl -> flowRepository.findById(
            dsl,
            companyId,
            draft.id(),
            1
        ).orElseThrow());
        assertTrue(!restoredFirst.isDeleted());

        Execution execution = write(dsl -> {
            Execution created = Execution.create(
                companyId,
                restoredFlow.id(),
                2
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
        assertEquals(0, restoredExecution.lockVersion());
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
        staleFirst.cancel();
        staleSecond.cancel();
        write(dsl -> {
            executionRepository.save(dsl, staleFirst);
            return null;
        });
        assertThrows(WorkflowException.class, () -> write(dsl -> {
            executionRepository.save(dsl, staleSecond);
            return null;
        }));

        ExternalTask externalTask = ExternalTask.create(
            companyId,
            execution.id(),
            execution.taskRuns().getFirst().id()
        );
        write(dsl -> {
            externalTaskRepository.save(dsl, externalTask);
            return null;
        });
        ExternalTask waiting = read(dsl ->
            externalTaskRepository.findWaitingByTaskRunId(
                dsl,
                companyId,
                execution.taskRuns().getFirst().id()
            ).orElseThrow()
        );
        waiting.complete(Map.of("approved", "yes"));
        write(dsl -> {
            externalTaskRepository.save(dsl, waiting);
            return null;
        });
        ExternalTask completed = read(dsl ->
            externalTaskRepository.findById(
                dsl,
                companyId,
                externalTask.id()
            ).orElseThrow()
        );
        assertEquals(ExternalTaskStatus.COMPLETED, completed.status());
        assertEquals(Map.of("approved", "yes"), completed.outputs());
        assertTrue(completed.lockVersion() > 0);
    }

    @Test
    void schemaUsesAggregateTypesInsteadOfDraftColumns() {
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

        int draftColumnCount = read(dsl -> dsl.fetchCount(
            columns,
            tableSchema.eq("public")
                .and(tableName.in("flow_drafts", "flows"))
                .and(columnName.eq("draft"))
        ));

        assertEquals(0, draftColumnCount);
    }

    @Test
    void persistsDeletionFactsWithoutFallingBackFromDeletedLatest() {
        ActorRef actor = ActorRef.create(
            "lifecycle-user",
            "Lifecycle Test"
        );
        long createdAt = 1_785_312_000_000L;
        FlowDraft draft = FlowDraft.create(
            companyId,
            "key: lifecycle-flow",
            actor,
            createdAt
        );
        write(dsl -> {
            draftRepository.save(dsl, draft);
            return null;
        });

        Flow first = Flow.deploy(
            companyId,
            draft.id(),
            definition("lifecycle-flow", "first", "prepare"),
            null,
            taskTypeDispatcher,
            actor,
            createdAt + 1_000L
        );
        write(dsl -> {
            flowRepository.save(dsl, first);
            return null;
        });
        Flow second = Flow.deploy(
            companyId,
            draft.id(),
            definition("lifecycle-flow", "second", "prepare"),
            first,
            taskTypeDispatcher,
            actor,
            createdAt + 2_000L
        );
        write(dsl -> {
            flowRepository.save(dsl, second);
            return null;
        });

        draft.delete(actor, createdAt + 3_000L);
        second.delete(actor, createdAt + 3_000L);
        write(dsl -> {
            draftRepository.save(dsl, draft);
            flowRepository.save(dsl, second);
            return null;
        });

        FlowDraft deletedDraft = read(dsl ->
            draftRepository.findById(
                dsl,
                companyId,
                draft.id()
            ).orElseThrow()
        );
        assertTrue(deletedDraft.isDeleted());
        assertTrue(read(dsl -> draftRepository.lockById(
            dsl,
            companyId,
            draft.id()
        )).isEmpty());
        Flow latest = read(dsl -> flowRepository.findLatest(
            dsl,
            companyId,
            draft.id()
        ).orElseThrow());
        assertEquals(2L, latest.reversion());
        assertTrue(latest.isDeleted());

        Flow historical = read(dsl -> flowRepository.findById(
            dsl,
            companyId,
            draft.id(),
            1L
        ).orElseThrow());
        assertFalse(historical.isDeleted());
        assertEquals(
            Boolean.TRUE,
            read(dsl -> dsl.select(FLOW_DRAFTS.DELETED)
                .from(FLOW_DRAFTS)
                .where(FLOW_DRAFTS.COMPANY_ID.eq(companyId))
                .and(FLOW_DRAFTS.ID.eq(draft.id()))
                .fetchOne(FLOW_DRAFTS.DELETED))
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
                "key", taskKey,
                "type", "AUTO",
                "inputs", List.of(Map.of(
                    "key", "request",
                    "type", "STRING",
                    "displayName", "Request",
                    "required", false
                )),
                "outputs", List.of(Map.of(
                    "key", "approved",
                    "type", "STRING"
                )),
                "route", "DIRECT",
                "tasks", List.of(Map.of(
                    "key", "approval",
                    "type", "PAUSE",
                    "outputs", List.of(Map.of(
                        "key", "approved",
                        "type", "STRING"
                    )),
                    "route", "outputs.approved == \"yes\""
                ))
            ))
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
            DSLContext dsl = DSL.using(connection, SQLDialect.POSTGRES);
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

    private static Session<User> session() {
        Session<User> session = new Session<>();
        session.setId("repository-test-session");
        User user = new User();
        user.setId("repository-user");
        user.setName("Repository Test");
        session.setUser(user);
        return session;
    }
}
