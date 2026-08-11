package org.cses.flow.core.services.externaltasks;

import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.externaltasks.ExternalTask;
import org.cses.flow.core.domains.externaltasks.ExternalTaskStatus;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.services.executions.ExecutionService;
import org.cses.flow.core.services.flows.FlowService;
import org.cses.flow.infrastructure.jooq.PostgresJooqTestAdapter;
import org.cses.flow.infrastructure.jooq.FlowDatabase;
import org.paas.session.Session;
import org.paas.session.User;
import org.x9.jooq.JOOQ;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Simulates an external actor in a server context that does not share the
 * starter context's services, repositories, or domain objects.
 */
public final class PostgresExternalTriggerRunner {

    private static final int MAX_TRIGGER_ROUNDS = 32;

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

    private PostgresExternalTriggerRunner() {
    }

    public static void completeWaiting(Set<String> companyIds) {
        if (companyIds == null || companyIds.isEmpty()) {
            return;
        }
        PostgresJooqTestAdapter jooq =
            PostgresJooqTestAdapter.fromEnvironment();
        ApplicationContext context = ApplicationContext.builder()
            .properties(PROPERTIES)
            .build();
        context.registerSingleton(
            JOOQ.class,
            jooq,
            Qualifiers.byName(FlowDatabase.DATA_SOURCE_NAME)
        );
        try (context) {
            context.start();

            ExternalTaskService service =
                context.getBean(ExternalTaskService.class);
            ExecutionService executionService =
                context.getBean(ExecutionService.class);
            FlowService flowService = context.getBean(FlowService.class);
            for (int round = 0; round < MAX_TRIGGER_ROUNDS; round++) {
                boolean completedAny = false;
                for (String companyId : companyIds) {
                    Session<User> session = session(companyId);
                    for (ExternalTask task : service.waitingTasks(session)) {
                        complete(
                            service,
                            executionService,
                            flowService,
                            session,
                            task
                        );
                        completedAny = true;
                    }
                }
                if (!completedAny) {
                    return;
                }
            }
            throw new IllegalStateException(
                "External trigger exceeded "
                    + MAX_TRIGGER_ROUNDS
                    + " recovery rounds for companies "
                    + companyIds
            );
        }
    }

    private static void complete(
        ExternalTaskService service,
        ExecutionService executionService,
        FlowService flowService,
        Session<User> session,
        ExternalTask task
    ) {
        if (task.status() != ExternalTaskStatus.WAITING) {
            return;
        }
        service.complete(
            session,
            task.id(),
            outputsFor(
                taskOutputKeys(
                    executionService,
                    flowService,
                    session,
                    task
                )
            )
        );
    }

    private static List<String> taskOutputKeys(
        ExecutionService executionService,
        FlowService flowService,
        Session<User> session,
        ExternalTask externalTask
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
            .outputs()
            .stream()
            .map(Output::getKey)
            .toList();
    }

    private static Map<String, Object> outputsFor(
        List<String> outputKeys
    ) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        for (String output : outputKeys) {
            String value = output.toLowerCase().contains("decision")
                ? "APPROVED"
                : "PASS";
            outputs.put(output, value);
        }
        return Map.copyOf(outputs);
    }

    private static Session<User> session(String companyId) {
        User user = new User();
        user.setId("external-trigger");
        user.setName("UC External Trigger");
        user.setCompanyId(companyId);
        Session<User> session = new Session<>();
        session.setUser(user);
        return session;
    }
}
