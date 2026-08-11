package org.cses.flow.core.services.executions;

import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.externaltasks.ExternalTask;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowDraft;
import org.cses.flow.core.plugins.PluginRegistry;
import org.cses.flow.core.services.externaltasks.ExternalTaskService;
import org.cses.flow.core.services.externaltasks.PostgresExternalTriggerRunner;
import org.cses.flow.core.services.flows.FlowService;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.infrastructure.jooq.PostgresJooqTestAdapter;
import org.cses.flow.infrastructure.jooq.FlowDatabase;
import org.paas.session.Session;
import org.paas.session.User;
import org.paas.common.util.StringUtil;
import org.x9.jooq.JOOQ;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private ExternalTaskService externalTaskService;
    private final Session<User> session;
    private final PostgresJooqTestAdapter jooq;
    private final boolean triggerWaitingOnClose;
    private final boolean cleanupOnClose;
    private final Map<String, Object> properties;
    private final Object[] singletons;
    private final Set<String> companyIds = new LinkedHashSet<>();

    private WorkflowUcFixture(
        PostgresJooqTestAdapter jooq,
        boolean triggerWaitingOnClose,
        String companyId,
        boolean cleanupOnClose,
        Map<String, Object> additionalProperties,
        Object... singletons
    ) {
        this.jooq = jooq;
        this.triggerWaitingOnClose = triggerWaitingOnClose;
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
        return open(false, true, null, Map.of());
    }

    public static WorkflowUcFixture openLeavingWaiting(
        String companyId
    ) {
        return open(false, false, companyId, Map.of());
    }

    public static WorkflowUcFixture openWithSingletons(
        Object... singletons
    ) {
        return open(false, true, null, Map.of(), singletons);
    }

    public static WorkflowUcFixture openWithProperties(
        Map<String, Object> properties
    ) {
        return open(false, true, null, properties);
    }

    private static WorkflowUcFixture open(
        boolean triggerWaitingOnClose,
        boolean cleanupOnClose,
        String companyId,
        Map<String, Object> properties,
        Object... singletons
    ) {
        PostgresJooqTestAdapter jooq =
            PostgresJooqTestAdapter.fromEnvironment();
        return new WorkflowUcFixture(
            jooq,
            triggerWaitingOnClose,
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

    public ExternalTaskService externalTaskService() {
        return externalTaskService;
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
        FlowDraft draft = flowService.saveDraft(session, yaml);
        return flowService.deploy(session, draft.id());
    }

    public ExternalTask waiting(Execution execution) {
        return waitingForExecution(execution.id());
    }

    public ExternalTask waitingForExecution(String executionId) {
        List<ExternalTask> waiting = externalTaskService.waitingTasks(session)
            .stream()
            .filter(task -> task.executionId().equals(executionId))
            .toList();
        if (waiting.size() != 1) {
            throw new IllegalStateException(
                "Expected one WAITING ExternalTask for Execution "
                    + executionId
                    + " but found "
                    + waiting.size()
            );
        }
        return waiting.getFirst();
    }

    public ExternalTask waitingForOutput(
        String executionId,
        String output
    ) {
        return externalTaskService.waitingTasks(session).stream()
            .filter(task -> task.executionId().equals(executionId))
            .filter(task -> declaresOutput(task, output))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No WAITING ExternalTask for Execution "
                    + executionId
                    + " and output "
                    + output
            ));
    }

    private boolean declaresOutput(
        ExternalTask externalTask,
        String output
    ) {
        Execution execution = executionService.execution(
            session,
            externalTask.executionId()
        ).orElseThrow(() -> new IllegalStateException(
            "ExternalTask references a missing Execution: "
                + externalTask.id()
        ));
        Flow flow = flowService.flow(
            session,
            execution.flowId(),
            execution.flowReversion()
        ).orElseThrow(() -> new IllegalStateException(
            "Execution references a missing Flow reversion: "
                + execution.id()
        ));
        String taskId = execution.requireTaskRun(
            externalTask.taskRunId()
        ).taskId();
        return flow.findTask(taskId)
            .orElseThrow(() -> new IllegalStateException(
                "TaskRun references a missing Task: " + taskId
            ))
            .declaresOutput(output);
    }

    public Execution completeAfterRestart(
        String executionId,
        Map<String, Object> outputs
    ) {
        restartServer();
        ExternalTask task = waitingForExecution(executionId);
        return externalTaskService.complete(session, task.id(), outputs);
    }

    public Execution completeAfterRestart(
        String executionId,
        String output,
        Map<String, Object> outputs
    ) {
        restartServer();
        ExternalTask task = waitingForOutput(executionId, output);
        return externalTaskService.complete(session, task.id(), outputs);
    }

    public static String pauseYaml(
        String key,
        String description,
        boolean trailingAutomaticTask
    ) {
        String trailing = trailingAutomaticTask
            ? """
              - key: record-result
                type: org.cses.flow.extensions.tasks.AutomaticTask
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
                  type: org.cses.flow.extensions.tasks.AutomaticTask
                resume:
                  - key: decision
                    type: STRING
            %s
            """.formatted(key, description, trailing);
    }

    @Override
    public void close() {
        try {
            closeServer();
            if (triggerWaitingOnClose) {
                PostgresExternalTriggerRunner.completeWaiting(companyIds);
            }
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
        externalTaskService = context.getBean(ExternalTaskService.class);
    }

    private void closeServer() {
        if (context != null) {
            context.close();
            context = null;
            flowService = null;
            executionService = null;
            externalTaskService = null;
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
}
