package org.cses.flow.infrastructure.queues.pulsar;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Executable;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ShutdownEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Singleton;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.PulsarClient;
import org.cses.flow.executor.ExecutorEvent;
import org.cses.flow.executor.commands.ExecutionCommand;
import org.paas.pulsar.PulsarFactory;
import org.paas.pulsar.PulsarLifecycleManager;

import java.util.List;
import java.util.Map;

/** Real broker configuration and owned-resource cleanup shared by integration tests. */
public class PulsarTestEnvironment {
    /** Prevents creation of this static test utility. */
    private PulsarTestEnvironment() {}

    /**
     * 通过真实 Micronaut 容器初始化 PAAS JSON 与 JacksonSchema 依赖，随后关闭容器；
     * 仅序列化测试使用，不启用消费者或连接测试 Broker。
     */
    public static void initializeJson() {
        try (ApplicationContext context = ApplicationContext.builder().deduceEnvironment(false)
            .properties(Map.of("pulsar.consumer.enabled", false,
                "datasources.default.enabled", false, "micronaut.config-client.enabled", false,
                "consul.client.registration.enabled", false, "consul.client.watch.service.enabled", false,
                "grpc.server.enabled", false, "thrift.server.enabled", false, "jooq.send-event", false))
            .start()) {
            context.getBean(org.paas.json.Jackson.class);
            context.getBean(org.paas.json.JsonFactory.class);
        }
    }

    /**
     * @return PAAS host properties pointing only to the explicitly selected test broker
     * @throws IllegalStateException when either test endpoint is missing
     */
    public static Map<String, Object> properties() {
        return Map.of(
            "flow.pulsar.integration-test", true,
            "pulsar.consumer.enabled", true,
            "pulsar.service-url", required("FLOW_PULSAR_TEST_URL"),
            "pulsar.admin-url", required("FLOW_PULSAR_TEST_ADMIN_URL"),
            "pulsar.development", false,
            "pulsar.io-threads", 2,
            "pulsar.listener-threads", 2
        );
    }

    /**
     * Clears only the dedicated Executor subscriptions after their context is closed.
     * Restart never calls this operation; physical cleanup is not business completion.
     * @throws IllegalStateException when the dedicated broker cannot be cleaned
     */
    public static void clearExecutorBacklogs() {
        try (PulsarAdmin admin = admin()) {
            for (String name : List.of(ExecutionCommand.QUEUE_NAME, ExecutorEvent.QUEUE_NAME)) {
                try {
                    admin.topics().skipAllMessages("persistent://public/default/" + name, name);
                } catch (PulsarAdminException.NotFoundException ignored) {
                    // Definition-only tests may never have created a topic or subscription.
                }
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot clean dedicated Pulsar test subscriptions", failure);
        }
    }

    /**
     * @return independently owned admin connection, closed by the caller
     * @throws Exception when the admin client cannot be created
     */
    public static PulsarAdmin admin() throws Exception {
        return PulsarAdmin.builder().serviceHttpUrl(required("FLOW_PULSAR_TEST_ADMIN_URL")).build();
    }

    /**
     * Requires an explicit test endpoint instead of falling back to a host business broker.
     * @param name required environment variable
     * @return supplied nonblank endpoint
     */
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required for real Pulsar tests");
        return value;
    }

    /** Retains PAAS consumers while excluding unrelated host WebSocket registrations. */
    @Singleton
    @Executable
    @Replaces(PulsarLifecycleManager.class)
    @Requires(property = "flow.pulsar.integration-test", value = "true")
    public static class Lifecycle extends PulsarLifecycleManager {
        private PulsarClient client;

        /**
         * @param factory real PAAS publisher and consumer registry
         * @param client real network client created by the PAAS factory
         */
        public Lifecycle(PulsarFactory factory, PulsarClient client) {
            super(List.of(), factory);
            this.client = client;
        }

        /**
         * Closes real subscriptions and producers before releasing the client threads.
         * @param event actual context shutdown
         * @throws Exception when PAAS or client shutdown fails
         */
        @Override
        @EventListener
        public void onShutdownEvent(ShutdownEvent event) throws Exception {
            try {
                super.onShutdownEvent(event);
            } finally {
                client.close();
            }
        }
    }
}
