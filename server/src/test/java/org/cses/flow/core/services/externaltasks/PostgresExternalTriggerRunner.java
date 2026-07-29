package org.cses.flow.core.services.externaltasks;

import io.micronaut.context.ApplicationContext;
import org.cses.flow.core.domains.externaltasks.ExternalTask;
import org.cses.flow.core.domains.externaltasks.ExternalTaskStatus;
import org.cses.flow.infrastructure.jooq.PostgresJooqTestAdapter;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Simulates an external actor in a server context that does not share the
 * starter context's services, repositories, or domain objects.
 */
public final class PostgresExternalTriggerRunner {

    private static final int MAX_TRIGGER_ROUNDS = 32;

    private static final Map<String, Object> PROPERTIES = Map.of(
        "flow.memory.enabled", false,
        "datasources.default.enabled", false,
        "flyway.datasources.default.enabled", false,
        "micronaut.config-client.enabled", false,
        "consul.client.registration.enabled", false,
        "consul.client.watch.service.enabled", false,
        "grpc.server.enabled", false,
        "thrift.server.enabled", false
    );

    private PostgresExternalTriggerRunner() {
    }

    public static void completeWaiting(Set<String> companyIds) {
        if (companyIds == null || companyIds.isEmpty()) {
            return;
        }
        PostgresJooqTestAdapter jooq =
            PostgresJooqTestAdapter.fromEnvironment();
        try (ApplicationContext context = ApplicationContext.builder()
            .properties(PROPERTIES)
            .singletons(jooq)
            .start()) {

            ExternalTaskService service =
                context.getBean(ExternalTaskService.class);
            for (int round = 0; round < MAX_TRIGGER_ROUNDS; round++) {
                boolean completedAny = false;
                for (String companyId : companyIds) {
                    Session<User> session = session(companyId);
                    for (ExternalTask task : service.waitingTasks(session)) {
                        complete(service, session, task);
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
        Session<User> session,
        ExternalTask task
    ) {
        if (task.status() != ExternalTaskStatus.WAITING) {
            return;
        }
        service.complete(
            session,
            task.id(),
            outputsFor(task.allowedOutputs())
        );
    }

    private static Map<String, Object> outputsFor(
        Set<String> allowedOutputs
    ) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        for (String output : allowedOutputs) {
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
