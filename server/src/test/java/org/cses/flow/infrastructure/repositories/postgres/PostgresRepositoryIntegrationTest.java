package org.cses.flow.infrastructure.repositories.postgres;

import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.externaltasks.ExternalTask;
import org.cses.flow.core.domains.externaltasks.ExternalTaskStatus;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowStatus;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.domains.tasks.TaskTypeDispatcher;
import org.cses.flow.core.exceptions.shared.WorkflowException;
import org.cses.flow.infrastructure.repositories.executions.postgres.ExecutionPostgresRepository;
import org.cses.flow.infrastructure.repositories.externaltasks.postgres.ExternalTaskPostgresRepository;
import org.cses.flow.infrastructure.repositories.flows.postgres.FlowPostgresRepository;
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
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.cses.flow.extensions.tasks.TaskPluginTestSupport.builtInDispatcher;
import static org.flow.gen.flow.Tables.ASSIGNMENT;
import static org.flow.gen.flow.Tables.EXECUTIONS;
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
            dsl.deleteFrom(ASSIGNMENT)
                .where(ASSIGNMENT.COMPANY_ID.eq(companyId))
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
        Flow draft = Flow.createDraft(
            companyId,
            definition("postgres-flow", "first", "prepare"),
            taskTypeDispatcher
        );
        write(dsl -> {
            flowRepository.save(dsl, draft);
            return null;
        });

        Flow firstPublish = write(dsl -> {
            Flow loaded = flowRepository.findById(
                dsl,
                companyId,
                draft.id()
            ).orElseThrow();
            loaded.publish();
            flowRepository.save(dsl, loaded);
            return loaded;
        });
        String stableTaskId = firstPublish.tasks(1).getFirst().id();

        write(dsl -> {
            Flow loaded = flowRepository.findById(
                dsl,
                companyId,
                draft.id()
            ).orElseThrow();
            loaded.createUpgradeDraft();
            flowRepository.save(dsl, loaded);
            return null;
        });
        write(dsl -> {
            Flow loaded = flowRepository.findById(
                dsl,
                companyId,
                draft.id()
            ).orElseThrow();
            loaded.saveDraft(
                definition("postgres-flow", "second", "prepare"),
                taskTypeDispatcher
            );
            flowRepository.save(dsl, loaded);
            return null;
        });
        Flow secondPublish = write(dsl -> {
            Flow loaded = flowRepository.findById(
                dsl,
                companyId,
                draft.id()
            ).orElseThrow();
            loaded.publish();
            flowRepository.save(dsl, loaded);
            return loaded;
        });

        Flow restoredFlow = read(dsl -> flowRepository.findByKey(
            dsl,
            companyId,
            "postgres-flow"
        ).orElseThrow());
        assertEquals(FlowStatus.DEPLOYED, restoredFlow.status());
        assertEquals(2, restoredFlow.latestDeployedVersion());
        assertEquals(
            stableTaskId,
            secondPublish.tasks(2).getFirst().id()
        );
        assertEquals(
            stableTaskId,
            restoredFlow.tasks(2).getFirst().id()
        );
        assertEquals(
            List.of(Output.create("approved", "STRING")),
            restoredFlow.tasks(2).getFirst().outputs()
        );
        assertEquals(
            "PAUSE",
            restoredFlow.tasks(2).getFirst().tasks().getFirst().type()
        );

        Execution execution = write(dsl -> {
            Execution created = Execution.create(
                companyId,
                restoredFlow.id(),
                2
            );
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
        staleFirst.beginModification();
        staleSecond.beginModification();
        staleFirst.cancelRunningTaskRuns();
        staleSecond.cancelRunningTaskRuns();
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
            execution.taskRuns().getFirst().id(),
            Set.of("approved")
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
                    "type", "STRING"
                )),
                "outputs", List.of(Map.of(
                    "key", "approved",
                    "type", "STRING"
                )),
                "route", "DIRECT",
                "retry", 1,
                "tasks", List.of(Map.of(
                    "key", "approval",
                    "type", "PAUSE",
                    "outputs", List.of(Map.of(
                        "key", "approved",
                        "type", "STRING"
                    )),
                    "route", "outputs.approved == \"yes\"",
                    "wait", true
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
