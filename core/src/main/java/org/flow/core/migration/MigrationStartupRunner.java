package org.flow.core.migration;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * Triggers database migration on {@code ApplicationContext.start()} only when explicitly enabled
 * with {@code flow.migration.auto=true}; automatic migration is disabled by default.
 *
 * <p>
 * This bean is {@code @Context} and ordered {@link Ordered#HIGHEST_PRECEDENCE}, so it is eagerly
 * initialized before any repository or service bean queries the database. It delegates to
 * {@link MigrationRunnerInterface#autoRun()} to apply pending scripts.
 *
 * <p>
 * The startup trigger is intentionally kept separate from {@link MigrationRunner} so the runner
 * itself is an ordinary {@code @Singleton}. Applications can resolve the runner and explicitly
 * invoke migration without enabling this startup trigger.
 *
 * <p>
 * Workers access the shared repository but do not own schema migration. Deployments commonly
 * share a single configuration across server types; only the designated host should migrate it.
 */
@Slf4j
@Context
@Order(Ordered.HIGHEST_PRECEDENCE)
@Requires(property = "flow.repository.type")
@Requires(property = "flow.server-type", notEquals = "WORKER")
@Requires(property = "flow.migration.auto", value = "true", defaultValue = "false")
public class MigrationStartupRunner {

    private final MigrationRunnerInterface migrationRunner;

    @Inject
    public MigrationStartupRunner(final MigrationRunnerInterface migrationRunner) {
        this.migrationRunner = migrationRunner;
    }

    @PostConstruct
    @SneakyThrows
    void onStartup() {
        migrationRunner.autoRun();
    }
}
