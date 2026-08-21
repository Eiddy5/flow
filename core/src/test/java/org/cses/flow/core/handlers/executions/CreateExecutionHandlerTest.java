package org.cses.flow.core.handlers.executions;

import org.cses.flow.core.services.CommandContext;
import org.cses.flow.core.services.executions.commands.CreateExecutionCommand;
import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.services.executions.handlers.CreateExecutionHandler;
import org.cses.flow.extensions.tasks.AutomaticTask;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CreateExecutionHandlerTest {

    private static final String COMPANY_ID = "company-1";

    @Test
    void replaysTheSameStableIdAgainstTheOriginalExactReversion() {
        FlowRepositoryStub flows = new FlowRepositoryStub();
        Flow first = flow("flow-key", null);
        Flow latest = flow("flow-key", first);
        flows.add(first);
        flows.add(latest);
        ExecutionRepositoryStub executions = new ExecutionRepositoryStub();
        CreateExecutionHandler handler = handler(flows, executions);
        CreateExecutionCommand command = pending(
            "execution-1",
            "flow-key",
            1
        );

        Execution created = handle(handler, command);
        Execution replayed = handle(handler, command);

        assertEquals("execution-1", created.id());
        assertEquals("execution-1", replayed.id());
        assertEquals("flow-key", created.flowKey());
        assertEquals("flow-key", replayed.flowKey());
        assertEquals(1L, created.flowVersion());
        assertEquals(1L, replayed.flowVersion());
        assertEquals(1, executions.saves);
        assertEquals(
            List.of("flow-key:1", "flow-key:1"),
            flows.exactReads
        );
        assertEquals(0, flows.latestReads);
    }

    @Test
    void rejectsReusingTheStableIdForAnotherFlow() {
        FlowRepositoryStub flows = new FlowRepositoryStub();
        flows.add(flow("flow-key-1", null));
        ExecutionRepositoryStub executions = new ExecutionRepositoryStub();
        CreateExecutionHandler handler = handler(flows, executions);
        handle(handler, pending("execution-1", "flow-key-1", 1));

        assertThrows(
            WorkflowException.class,
            () -> handle(
                handler,
                pending("execution-1", "flow-key-2", 1)
            )
        );
        assertEquals(1, executions.saves);
    }

    @Test
    void rejectsReusingTheStableIdForAnotherFlowReversion() {
        FlowRepositoryStub flows = new FlowRepositoryStub();
        Flow first = flow("flow-key", null);
        flows.add(first);
        flows.add(flow("flow-key", first));
        ExecutionRepositoryStub executions = new ExecutionRepositoryStub();
        CreateExecutionHandler handler = handler(flows, executions);
        handle(handler, pending("execution-1", "flow-key", 1));

        assertThrows(
            WorkflowException.class,
            () -> handle(
                handler,
                pending("execution-1", "flow-key", 2)
            )
        );
        assertEquals(1, executions.saves);
    }

    private static CreateExecutionHandler handler(
        FlowRepository flows,
        ExecutionRepository executions
    ) {
        return new CreateExecutionHandler(
            flows,
            executions
        );
    }

    private static CreateExecutionCommand pending(
        String executionId,
        String flowKey,
        long flowVersion
    ) {
        return CreateExecutionCommand.from(
            executionId,
            flowKey,
            flowVersion
        );
    }

    private static Execution handle(
        CreateExecutionHandler handler,
        CreateExecutionCommand command
    ) {
        command.validate();
        return handler.handle(CommandContext.from(
            command,
            session(),
            DSL.using(SQLDialect.POSTGRES)
        ));
    }

    private static Flow flow(String key, Flow latest) {
        return Flow.deploy(
            COMPANY_ID,
            key,
            "",
            List.of(),
            List.of(),
            List.of(AutomaticTask.builder()
                .id("task-1")
                .key("task")
                .build()),
            latest,
            ActorRef.create("creator-1", "Creator"),
            latest == null ? 1_000L : latest.createdAt() + 1
        );
    }

    private static Session<User> session() {
        User user = new User();
        user.setId("creator-1");
        user.setCompanyId(COMPANY_ID);
        Session<User> session = new Session<>();
        session.setCompanyId(COMPANY_ID);
        session.setUser(user);
        return session;
    }

    private static final class FlowRepositoryStub
        implements FlowRepository {

        private final Map<FlowKey, Flow> flows = new LinkedHashMap<>();
        private final List<String> exactReads = new ArrayList<>();
        private int latestReads;

        void add(Flow flow) {
            flows.put(
                FlowKey.from(
                    flow.companyId(),
                    flow.key(),
                    flow.reversion()
                ),
                flow.copy()
            );
        }

        @Override
        public Optional<Flow> findByKey(
            DSLContext dsl,
            String companyId,
            String flowKey,
            long flowVersion
        ) {
            exactReads.add(flowKey + ":" + flowVersion);
            return Optional.ofNullable(flows.get(
                FlowKey.from(companyId, flowKey, flowVersion)
            )).map(Flow::copy);
        }

        @Override
        public Optional<Flow> findLatestByKey(
            DSLContext dsl,
            String companyId,
            String flowKey
        ) {
            latestReads++;
            return flows.entrySet().stream()
                .filter(entry -> entry.getKey().companyId().equals(companyId))
                .filter(entry -> entry.getKey().flowKey().equals(flowKey))
                .max(Map.Entry.comparingByKey((left, right) ->
                    Long.compare(left.reversion(), right.reversion())
                ))
                .map(Map.Entry::getValue)
                .map(Flow::copy);
        }

        @Override
        public void save(DSLContext dsl, Flow flow) {
            add(flow);
        }
    }

    private static final class ExecutionRepositoryStub
        implements ExecutionRepository {

        private final Map<ExecutionKey, Execution> executions =
            new LinkedHashMap<>();
        private int saves;

        @Override
        public Optional<Execution> findById(
            DSLContext dsl,
            String companyId,
            String executionId
        ) {
            return Optional.ofNullable(executions.get(
                ExecutionKey.from(companyId, executionId)
            )).map(Execution::copy);
        }

        @Override
        public Optional<Execution> lockById(
            DSLContext dsl,
            String companyId,
            String executionId
        ) {
            return findById(dsl, companyId, executionId);
        }

        @Override
        public List<Execution> findAll(
            DSLContext dsl,
            String companyId
        ) {
            return executions.entrySet().stream()
                .filter(entry -> entry.getKey().companyId().equals(companyId))
                .map(Map.Entry::getValue)
                .map(Execution::copy)
                .toList();
        }

        @Override
        public long count(DSLContext dsl, String companyId) {
            return executions.keySet().stream()
                .filter(key -> key.companyId().equals(companyId))
                .count();
        }

        @Override
        public void save(DSLContext dsl, Execution execution) {
            saves++;
            executions.put(
                ExecutionKey.from(execution.companyId(), execution.id()),
                execution.copy()
            );
        }
    }

    private record FlowKey(
        String companyId,
        String flowKey,
        long reversion
    ) {

        private static FlowKey from(
            String companyId,
            String flowKey,
            long reversion
        ) {
            return new FlowKey(companyId, flowKey, reversion);
        }
    }

    private record ExecutionKey(String companyId, String executionId) {

        private static ExecutionKey from(
            String companyId,
            String executionId
        ) {
            return new ExecutionKey(companyId, executionId);
        }
    }
}
