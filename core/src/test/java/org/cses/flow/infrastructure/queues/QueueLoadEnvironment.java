package org.cses.flow.infrastructure.queues;

import io.micronaut.context.ApplicationContext;
import io.micronaut.configuration.jdbc.hikari.HikariUrlDataSource;
import io.micronaut.inject.qualifiers.Qualifiers;
import org.cses.flow.queues.DispatchQueue;
import org.cses.flow.queues.event.DispatchEvent;
import org.cses.flow.infrastructure.jooq.FlowDatabase;
import org.paas.common.util.StringUtil;
import org.x9.jooq.JOOQ;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.flow.gen.flow.Tables.DISPATCH_QUEUE_MESSAGES;

/**
 * Production-like named Flow datasource fixture for Queue load tests.
 */
final class QueueLoadEnvironment implements AutoCloseable {

    private final ApplicationContext context;
    private final JOOQ jooq;
    private final String queuePrefix;
    private final int shutdownTimeoutSeconds;
    private final Set<DefaultDispatchQueue<?>> queues =
        ConcurrentHashMap.newKeySet();

    private QueueLoadEnvironment(
        ApplicationContext context,
        JOOQ jooq,
        String queuePrefix,
        int shutdownTimeoutSeconds
    ) {
        this.context = context;
        this.jooq = jooq;
        this.queuePrefix = queuePrefix;
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
    }

    static QueueLoadEnvironment open(
        int poolSize,
        int shutdownTimeoutSeconds
    ) {
        String url = requiredEnvironment("FLOW_POSTGRES_TEST_URL");
        String username = environment(
            "FLOW_POSTGRES_TEST_USER",
            "flow"
        );
        String password = environment(
            "FLOW_POSTGRES_TEST_PASSWORD",
            "flow"
        );
        ApplicationContext context = ApplicationContext.run(Map.ofEntries(
            Map.entry("datasources.flow.url", url),
            Map.entry(
                "datasources.flow.driver-class-name",
                "org.postgresql.Driver"
            ),
            Map.entry("datasources.flow.username", username),
            Map.entry("datasources.flow.password", password),
            Map.entry("datasources.flow.maximum-pool-size", poolSize),
            Map.entry(
                "datasources.flow.minimum-idle",
                Math.min(poolSize, 2)
            ),
            Map.entry("datasources.flow.connection-timeout", 10_000),
            Map.entry(
                "datasources.flow.connection-init-sql",
                "SET statement_timeout = "
                    + TimeUnit.SECONDS.toMillis(shutdownTimeoutSeconds)
            ),
            Map.entry(
                "datasources.flow.data-source-properties.socketTimeout",
                shutdownTimeoutSeconds
            ),
            Map.entry(
                "datasources.flow.data-source-properties.tcpKeepAlive",
                true
            ),
            Map.entry("jooq.datasources.flow.sql-dialect", "POSTGRES"),
            Map.entry("jooq.send-event", false),
            Map.entry("micronaut.health.monitor.enabled", false),
            Map.entry("micronaut.config-client.enabled", false),
            Map.entry("consul.client.registration.enabled", false),
            Map.entry("consul.client.config.enabled", false),
            Map.entry("grpc.server.enabled", false),
            Map.entry("thrift.server.enabled", false),
            Map.entry("pulsar.consumer.enabled", false)
        ));
        try {
            org.jooq.Configuration configuration = context.getBean(
                org.jooq.Configuration.class,
                Qualifiers.byName(FlowDatabase.DATA_SOURCE_NAME)
            );
            DataSource dataSource = context.getBean(
                DataSource.class,
                Qualifiers.byName(FlowDatabase.DATA_SOURCE_NAME)
            );
            JOOQ jooq = new JOOQ(
                configuration,
                unwrapHikari(dataSource)
            );
            return new QueueLoadEnvironment(
                context,
                jooq,
                "dispatch-queue-load-" + StringUtil.newId(),
                shutdownTimeoutSeconds
            );
        } catch (RuntimeException exception) {
            try {
                context.close();
            } catch (RuntimeException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    String runId(String scenario, int repetition) {
        return queuePrefix + "-" + scenario + "-" + repetition;
    }

    <T extends DispatchEvent> DispatchQueue<T> queue(
        String queueName,
        Class<T> eventType,
        long pollIntervalMillis
    ) {
        DefaultDispatchQueue<T> queue = new DefaultDispatchQueue<>(
            queueName,
            jooq,
            eventType,
            pollIntervalMillis
        );
        queues.add(queue);
        return queue;
    }

    int pendingMessages(String queueName) {
        return jooq.runReturn(dsl -> dsl.fetchCount(
            dsl.selectFrom(DISPATCH_QUEUE_MESSAGES)
                .where(DISPATCH_QUEUE_MESSAGES.QUEUE_NAME.eq(queueName))
        ));
    }

    int deleteMessages(String queueName) {
        return jooq.runReturn(dsl -> dsl.deleteFrom(DISPATCH_QUEUE_MESSAGES)
            .where(DISPATCH_QUEUE_MESSAGES.QUEUE_NAME.eq(queueName))
            .execute());
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        ExecutorService closeExecutor =
            Executors.newVirtualThreadPerTaskExecutor();
        try {
            CompletableFuture<?>[] closures = queues.stream()
                .map(queue -> CompletableFuture.runAsync(
                    queue::close,
                    closeExecutor
                ))
                .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(closures).get(
                shutdownTimeoutSeconds,
                TimeUnit.SECONDS
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failure = combine(
                failure,
                new IllegalStateException(
                    "Interrupted while closing Queue fixtures",
                    exception
                )
            );
        } catch (ExecutionException | TimeoutException exception) {
            failure = combine(
                failure,
                new IllegalStateException(
                    "Could not close Queue fixtures within "
                        + shutdownTimeoutSeconds
                        + " seconds",
                    exception
                )
            );
        } finally {
            closeExecutor.shutdownNow();
        }
        try {
            jooq.run(dsl -> dsl.deleteFrom(DISPATCH_QUEUE_MESSAGES)
                .where(
                    DISPATCH_QUEUE_MESSAGES.QUEUE_NAME.startsWith(
                        queuePrefix
                    )
                )
                .execute());
        } catch (RuntimeException exception) {
            failure = combine(failure, exception);
        }
        try {
            context.close();
        } catch (RuntimeException exception) {
            failure = combine(failure, exception);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException combine(
        RuntimeException existing,
        RuntimeException added
    ) {
        if (existing == null) {
            return added;
        }
        existing.addSuppressed(added);
        return existing;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static HikariUrlDataSource unwrapHikari(
        DataSource dataSource
    ) {
        try {
            return dataSource.unwrap(HikariUrlDataSource.class);
        } catch (SQLException exception) {
            throw new IllegalStateException(
                "Named flow DataSource is not backed by Hikari",
                exception
            );
        }
    }
}
