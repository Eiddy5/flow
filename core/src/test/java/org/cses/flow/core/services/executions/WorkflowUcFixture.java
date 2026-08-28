package org.cses.flow.core.services.executions;

import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.plugins.PluginRegistry;
import org.cses.flow.core.services.flows.FlowService;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.executor.commands.Create;
import org.cses.flow.infrastructure.jooq.PostgresJooqTestAdapter;
import org.cses.flow.infrastructure.jooq.FlowDatabase;
import org.paas.session.Session;
import org.paas.session.User;
import org.paas.common.util.StringUtil;
import org.x9.jooq.JOOQ;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public final class WorkflowUcFixture implements AutoCloseable {

    private static final Map<String, Object> PROPERTIES = Map.of(
        "datasources.default.enabled", false,
        "flyway.datasources.default.enabled", false,
        "micronaut.config-client.enabled", false,
        "consul.client.registration.enabled", false,
        "consul.client.watch.service.enabled", false,
        "grpc.server.enabled", false,
        "thrift.server.enabled", false,
        "pulsar.consumer.enabled", false,
        "jooq.send-event", false
    );

    private ApplicationContext context;
    private FlowService flowService;
    private ExecutionService executionService;
    private final Session<User> session;
    private final PostgresJooqTestAdapter jooq;
    private final boolean cleanupOnClose;
    private final Map<String, Object> properties;
    private final Object[] singletons;
    private final Set<String> companyIds = new LinkedHashSet<>();

    private WorkflowUcFixture(
        PostgresJooqTestAdapter jooq,
        String companyId,
        boolean cleanupOnClose,
        Map<String, Object> additionalProperties,
        Object... singletons
    ) {
        this.jooq = jooq;
        this.cleanupOnClose = cleanupOnClose;
        Map<String, Object> mergedProperties =
            new java.util.LinkedHashMap<>(PROPERTIES);
        if (additionalProperties != null) {
            mergedProperties.putAll(additionalProperties);
        }
        this.properties = Map.copyOf(mergedProperties);
        this.singletons = singletons == null
            ? new Object[0]
            : singletons.clone();
        this.session = newSession(companyId == null
            ? "uc-" + StringUtil.newId()
            : companyId);
        this.companyIds.add(session.getCompanyId());
        startServer();
    }

    public static WorkflowUcFixture open() {
        return open(true, null, Map.of());
    }

    public static WorkflowUcFixture openLeavingWaiting(
        String companyId
    ) {
        return open(false, companyId, Map.of());
    }

    public static WorkflowUcFixture openWithSingletons(
        Object... singletons
    ) {
        return open(true, null, Map.of(), singletons);
    }

    public static WorkflowUcFixture openWithProperties(
        Map<String, Object> properties
    ) {
        return open(true, null, properties);
    }

    private static WorkflowUcFixture open(
        boolean cleanupOnClose,
        String companyId,
        Map<String, Object> properties,
        Object... singletons
    ) {
        PostgresJooqTestAdapter jooq =
            PostgresJooqTestAdapter.fromEnvironment();
        return new WorkflowUcFixture(
            jooq,
            companyId,
            cleanupOnClose,
            properties,
            singletons
        );
    }

    public FlowService flowService() {
        return flowService;
    }

    public ExecutionService executionService() {
        return executionService;
    }

    public PluginRegistry pluginRegistry() {
        return context.getBean(PluginRegistry.class);
    }

    public Session<User> session() {
        return session;
    }

    /**
     * Closes the current server and starts a new server backed only by the
     * persisted PostgreSQL state.
     */
    public void restartServer() {
        closeServer();
        startServer();
    }

    public long executionCount() {
        ExecutionRepository repository =
            context.getBean(ExecutionRepository.class);
        JOOQ jooq = context.getBean(JOOQ.class);
        return jooq.get(dsl -> repository.count(
            dsl,
            session.getCompanyId()
        ));
    }

    public Flow deploy(String yaml) {
        Flow draft = flowService.save(
            session,
            PublishFlowCommand.from(yaml)
        );
        return flowService.save(
            session,
            PublishFlowCommand.from(draft.key(), false)
        );
    }

    /**
     * Starts a Flow and waits until its asynchronous first drive reaches an
     * observable stable state. Tests for Queue acceptance use create directly.
     */
    public Execution startAndAwait(Flow flow) {
        return startAndAwait(session, flow);
    }

    public Execution startAndAwait(
        Session<User> startSession,
        Flow flow
    ) {
        Execution accepted = startCreated(startSession, flow);
        return awaitStable(startSession, accepted.id());
    }

    /**
     * Publishes a Create command and waits only until its consumer has
     * materialized the accepted stable Execution identity.
     */
    public Execution startCreated(Flow flow) {
        return startCreated(session, flow);
    }

    public Execution startCreated(
        Session<User> startSession,
        Flow flow
    ) {
        Create accepted = executionService.create(startSession, flow.key());
        return awaitCreated(
            startSession,
            accepted.getExecutionId()
        );
    }

    private Execution awaitCreated(
        Session<User> querySession,
        String executionId
    ) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Optional<Execution> created = executionService.execution(
                querySession,
                executionId
            );
            if (created.isPresent()) {
                return created.orElseThrow();
            }
            awaitChangeSignal(deadline);
        }
        throw new IllegalStateException(
            "Create command did not materialize Execution: " + executionId
        );
    }

    public Execution awaitStable(Execution accepted) {
        return awaitStable(session, accepted.id());
    }

    public Execution awaitStable(String executionId) {
        return awaitStable(session, executionId);
    }

    public Execution awaitStable(
        Session<User> querySession,
        String executionId
    ) {
        return awaitExecution(
            querySession,
            executionId,
            execution -> execution.isTerminal()
                || execution.state().isPaused()
        );
    }

    public Execution awaitExecution(
        Session<User> querySession,
        String executionId,
        Predicate<Execution> expected
    ) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Execution observed = null;
        while (System.nanoTime() < deadline) {
            Optional<Execution> current = executionService.execution(
                querySession,
                executionId
            );
            if (current.isPresent()) {
                observed = current.orElseThrow();
                if (expected.test(observed)) {
                    return observed;
                }
            }
            awaitChangeSignal(deadline);
        }
        throw new IllegalStateException(
            "Execution did not reach the expected state: "
                + executionId
                + ", last state="
                + (observed == null ? "missing" : observed.state().current())
        );
    }

    public PausedTaskRunRef waiting(Execution execution) {
        return waitingForExecution(execution.id());
    }

    public PausedTaskRunRef waitingForExecution(String executionId) {
        return waitingForExecution(session, executionId);
    }

    public PausedTaskRunRef waitingForExecution(
        Session<User> querySession,
        String executionId
    ) {
        awaitExecution(
            querySession,
            executionId,
            execution -> execution.state().isPaused()
        );
        List<PausedTaskRunRef> waiting = pausedTaskRuns(querySession).stream()
            .filter(task -> task.executionId().equals(executionId))
            .toList();
        if (waiting.size() != 1) {
            throw new IllegalStateException(
                "Expected one PAUSED TaskRun for Execution "
                    + executionId
                    + " but found "
                    + waiting.size()
            );
        }
        return waiting.getFirst();
    }

    public PausedTaskRunRef waitingForOutput(
        String executionId,
        String output
    ) {
        return waitingForOutput(session, executionId, output);
    }

    public PausedTaskRunRef waitingForOutput(
        Session<User> querySession,
        String executionId,
        String output
    ) {
        awaitExecution(
            querySession,
            executionId,
            execution -> execution.state().isPaused()
        );
        return pausedTaskRuns(querySession).stream()
            .filter(task -> task.executionId().equals(executionId))
            .filter(task -> declaresOutput(querySession, task, output))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No PAUSED TaskRun for Execution "
                    + executionId
                    + " and output "
                    + output
            ));
    }

    public List<PausedTaskRunRef> pausedTaskRuns() {
        return pausedTaskRuns(session);
    }

    public List<PausedTaskRunRef> pausedTaskRuns(
        Session<User> querySession
    ) {
        return executionService.executions(querySession).stream()
            .flatMap(execution -> execution.pausedTaskRuns().stream()
                .map(taskRun -> PausedTaskRunRef.from(
                    execution.id(),
                    taskRun.id()
                )))
            .toList();
    }

    private boolean declaresOutput(
        Session<User> querySession,
        PausedTaskRunRef pausedTaskRun,
        String output
    ) {
        Execution execution = executionService.execution(
            querySession,
            pausedTaskRun.executionId()
        ).orElseThrow(() -> new IllegalStateException(
            "Paused TaskRun references a missing Execution: "
                + pausedTaskRun.taskRunId()
        ));
        Flow flow = flowService.flow(
            querySession,
            execution.flowKey(),
            execution.flowVersion()
        ).orElseThrow(() -> new IllegalStateException(
            "Execution references a missing Flow reversion: "
                + execution.id()
        ));
        String taskId = execution.requireTaskRun(
            pausedTaskRun.taskRunId()
        ).taskId();
        return flow.findTask(taskId)
            .orElseThrow(() -> new IllegalStateException(
                "TaskRun references a missing Task: " + taskId
            ))
            .declaresOutput(output);
    }

    public Execution resume(
        PausedTaskRunRef pausedTaskRun,
        Map<String, ?> outputs
    ) {
        return resume(session, pausedTaskRun, outputs);
    }

    public Execution cancel(String executionId) {
        return cancel(session, executionId);
    }

    public Execution cancel(
        Session<User> cancelSession,
        String executionId
    ) {
        Execution accepted = executionService.cancel(
            cancelSession,
            executionId
        );
        return awaitExecution(
            cancelSession,
            accepted.id(),
            Execution::isTerminal
        );
    }

    public Execution resume(
        Session<User> resumeSession,
        PausedTaskRunRef pausedTaskRun,
        Map<String, ?> outputs
    ) {
        Execution accepted = executionService.resume(
            resumeSession,
            pausedTaskRun.executionId(),
            pausedTaskRun.taskRunId(),
            outputs
        );
        awaitExecution(
            resumeSession,
            accepted.id(),
            execution -> execution.isTerminal()
                || !execution.requireTaskRun(pausedTaskRun.taskRunId())
                    .state()
                    .is(State.Type.PAUSED)
        );
        return awaitStable(resumeSession, accepted.id());
    }

    public TaskRun taskRun(
        Session<User> querySession,
        PausedTaskRunRef pausedTaskRun
    ) {
        return executionService.execution(
            querySession,
            pausedTaskRun.executionId()
        ).orElseThrow(() -> new IllegalStateException(
            "Execution does not exist: " + pausedTaskRun.executionId()
        )).requireTaskRun(pausedTaskRun.taskRunId());
    }

    public TaskRun taskRun(PausedTaskRunRef pausedTaskRun) {
        return taskRun(session, pausedTaskRun);
    }

    public Execution resumeAfterRestart(
        String executionId,
        Map<String, Object> outputs
    ) {
        restartServer();
        PausedTaskRunRef task = waitingForExecution(executionId);
        return resume(task, outputs);
    }

    public Execution resumeAfterRestart(
        String executionId,
        String output,
        Map<String, Object> outputs
    ) {
        restartServer();
        PausedTaskRunRef task = waitingForOutput(executionId, output);
        return resume(task, outputs);
    }

    public static String pauseYaml(
        String key,
        String description,
        boolean trailingLogTask
    ) {
        String trailing = trailingLogTask
            ? """
              - key: record-result
                type: org.cses.flow.extensions.log.Log
                message: "test step"
            """
            : "";
        return """
            key: %s
            description: %s
            tasks:
              - key: wait-confirmation
                type: org.cses.flow.extensions.flow.Pause
                pause:
                  key: create-confirmation
                  type: org.cses.flow.extensions.log.Log
                  message: "test step"
                resume:
                  - key: decision
                    type: STRING
                outputs:
                  - key: decision
                    type: STRING
            %s
            """.formatted(key, description, trailing);
    }

    @Override
    public void close() {
        try {
            closeServer();
        } finally {
            if (cleanupOnClose && jooq.cleanupEnabled()) {
                companyIds.forEach(jooq::removeTenant);
            }
        }
    }

    private void startServer() {
        var builder = ApplicationContext.builder()
            .properties(properties);
        if (singletons.length > 0) {
            builder.singletons(singletons);
        }
        context = builder.build();
        context.registerSingleton(
            JOOQ.class,
            jooq,
            Qualifiers.byName(FlowDatabase.DATA_SOURCE_NAME)
        );
        context.start();
        flowService = context.getBean(FlowService.class);
        executionService = context.getBean(ExecutionService.class);
    }

    private void closeServer() {
        if (context != null) {
            context.close();
            context = null;
            flowService = null;
            executionService = null;
        }
    }

    public Session<User> sessionFor(String tenantLabel) {
        String companyId = session.getCompanyId() + "-" + tenantLabel;
        companyIds.add(companyId);
        return newSession(companyId);
    }

    public Session<User> sessionForExactCompany(String companyId) {
        companyIds.add(companyId);
        return newSession(companyId);
    }

    private static Session<User> newSession(String companyId) {
        User user = new User();
        user.setId("user-1");
        user.setName("UC Test User");
        user.setCompanyId(companyId);
        Session<User> session = new Session<>();
        session.setUser(user);
        return session;
    }

    private static void awaitChangeSignal(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return;
        }
        try {
            new CountDownLatch(1).await(
                Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)),
                TimeUnit.NANOSECONDS
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Interrupted while awaiting Execution state",
                exception
            );
        }
    }

    public record PausedTaskRunRef(
        String executionId,
        String taskRunId
    ) {

        public static PausedTaskRunRef from(
            String executionId,
            String taskRunId
        ) {
            return new PausedTaskRunRef(executionId, taskRunId);
        }
    }
}
