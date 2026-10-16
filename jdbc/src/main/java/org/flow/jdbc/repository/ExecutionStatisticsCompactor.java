package org.flow.jdbc.repository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.jooq.DSLContext;
import org.jooq.Condition;
import org.jooq.impl.DSL;

import org.flow.gen.flow.Tables;
import org.flow.gen.flow.tables.ExecutionStatisticsTable;
import org.flow.core.metrics.MetricRegistry;
import org.flow.core.models.executions.statistics.ExecutionStatistic;
import org.flow.core.models.flows.State;
import org.flow.jdbc.JooqDSLContextWrapper;
import org.flow.jdbc.runner.JdbcRepositoryEnabled;

import io.micrometer.core.instrument.Timer;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import static org.flow.jdbc.repository.AbstractJdbcExecutionStatisticsRepository.bindableDate;

/**
 * Periodically compacts the {@code execution_statistics} table.
 * <p>
 * The table mixes raw per-execution rows ({@code count = 1}) with rows already merged by a
 * previous run ({@code count = N}); see {@link ExecutionStatistic} for why every read is a
 * uniform aggregation regardless of compaction state. This job bounds row growth: for every
 * closed minute bucket that still holds raw rows, it merges them into the bucket's aggregate row
 * and deletes the raw rows.
 * <p>
 * It is only used in JDBC backends, Elasticsearch didn't compact and always aggregate on the fly when querying.
 */
@Singleton
@JdbcRepositoryEnabled
@Requires(property = "flow.server-type", pattern = "(STANDALONE|WEBSERVER|INDEXER)")
@Slf4j
public class ExecutionStatisticsCompactor {

    private ExecutionStatisticsTable table() {
        return jdbcRepository.getTable(Tables.EXECUTION_STATISTICS);
    }

    // Safety cap on the number of findKeysWithRawRows() batches drained within a single tick, so
    // that a key which can never be compacted (e.g. a persistently corrupt row) can't keep the
    // batch full forever and block this thread indefinitely; the remainder is simply picked up on
    // the next tick. Not user-configurable: it exists purely to bound worst-case behavior, not to
    // be tuned.
    private static final int MAX_BATCHES_PER_TICK = 50;

    private final org.flow.jdbc.AbstractJdbcRepository<ExecutionStatistic> jdbcRepository;
    private final JooqDSLContextWrapper dslContextWrapper;
    private final int maxKeysPerRun;
    private final Timer compactionDurationTimer;

    @Inject
    public ExecutionStatisticsCompactor(
        @Named("executionstatistics") org.flow.jdbc.AbstractJdbcRepository<ExecutionStatistic> jdbcRepository,
        ExecutionStatisticCompactorConfig config,
        MetricRegistry metricRegistry) {
        this.jdbcRepository = jdbcRepository;
        this.dslContextWrapper = jdbcRepository.getDslContextWrapper();
        this.maxKeysPerRun = config.maxKeysPerRun();
        this.compactionDurationTimer = metricRegistry.timer(
            MetricRegistry.METRIC_JDBC_EXECUTION_STATISTICS_COMPACTOR_DURATION,
            MetricRegistry.METRIC_JDBC_EXECUTION_STATISTICS_COMPACTOR_DURATION_DESCRIPTION
        );
    }

    // @Scheduled requires compile-time constant placeholder strings, so initialDelay/fixedDelay
    // can't be read from the injected ExecutionStatisticCompactorConfig directly; the property
    // keys and defaults here must stay in sync with that config's prefix (same pattern as
    // org.flow.core.storages.kv.KVPurgeCleaner + KVPurgeConfiguration).
    @Scheduled(
        initialDelay = "${flow.jdbc.execution-statistics.compactor.initial-delay:1m}",
        fixedDelay = "${flow.jdbc.execution-statistics.compactor.fixed-delay:1m}"
    )
    public void compact() {
        compactionDurationTimer.record(this::drainBacklog);
    }

    private void drainBacklog() {
        Instant closedBefore = Instant.now().truncatedTo(ChronoUnit.MINUTES);

        int batches = 0;
        List<BucketGroupKey> keys;
        do {
            keys = findKeysWithRawRows(closedBefore);

            for (BucketGroupKey key : keys) {
                try {
                    compactKey(key, closedBefore);
                } catch (Exception e) {
                    // one key failing (e.g. a transient deadlock) must not prevent the others from being compacted;
                    // the row(s) will simply be retried on the next tick.
                    log.warn("Unable to compact execution statistics for {}: {}", key, e.getMessage(), e);
                }
            }

            batches++;
            // a full batch means there may be more keys waiting: keep draining within this tick
            // instead of waiting for the next fixedDelay, so a burst of activity doesn't push
            // compaction further and further behind.
        } while (keys.size() == maxKeysPerRun && batches < MAX_BATCHES_PER_TICK);

        if (keys.size() == maxKeysPerRun) {
            log.warn(
                "Execution statistics compaction hit its per-tick safety cap ({} batches of {} keys); the remaining backlog will be processed on the next tick.",
                MAX_BATCHES_PER_TICK, maxKeysPerRun
            );
        }
    }

    private Condition tenantCondition(String tenantId) {
        return tenantId == null ? table().TENANT_ID.isNull() : table().TENANT_ID.eq(tenantId);
    }

    private List<BucketGroupKey> findKeysWithRawRows(Instant closedBefore) {
        return dslContextWrapper.transactionResult(
            configuration -> DSL
                .using(configuration)
                .selectDistinct(table().TENANT_ID, table().NAMESPACE, table().FLOW_ID, table().STATE)
                .from(table())
                .where(table().EXECUTION_ID.isNotNull())
                .and(table().DATE.lessThan(bindableDate(closedBefore)))
                .limit(maxKeysPerRun)
                .fetch(
                    record -> new BucketGroupKey(
                        record.get(table().TENANT_ID),
                        record.get(table().NAMESPACE),
                        record.get(table().FLOW_ID),
                        record.get(table().STATE)
                    )
                )
        );
    }

    private void compactKey(BucketGroupKey key, Instant closedBefore) {
        dslContextWrapper.transaction(configuration ->
        {
            DSLContext context = DSL.using(configuration);

            List<ExecutionStatistic> rawRows = jdbcRepository.fetch(
                context.select(table().VALUE)
                    .from(table())
                    .where(tenantCondition(key.tenantId()))
                    .and(table().NAMESPACE.eq(key.namespace()))
                    .and(table().FLOW_ID.eq(key.flowId()))
                    .and(table().STATE.eq(key.state()))
                    .and(table().EXECUTION_ID.isNotNull())
                    .and(table().DATE.lessThan(bindableDate(closedBefore)))
                    .forUpdate()
                    .skipLocked()
            );

            if (rawRows.isEmpty()) {
                // either nothing left, or another compactor instance already claimed these rows
                return;
            }

            Map<Instant, List<ExecutionStatistic>> byBucket = rawRows.stream()
                .collect(Collectors.groupingBy(ExecutionStatistic::date));

            byBucket.forEach((bucketDate, bucketRows) -> mergeBucket(context, key, bucketDate, bucketRows));

            context.deleteFrom(table())
                .where(table().KEY.in(rawRows.stream().map(ExecutionStatistic::executionId).toList()))
                .execute();

            log.debug("Compacted {} execution statistics for {} into {} bucket(s)", rawRows.size(), key, byBucket.size());
        });
    }

    /**
     * Merges one closed bucket's raw rows into its aggregate row.
     * <p>
     * The aggregate row is fetched-or-created with {@link org.flow.jdbc.AbstractJdbcRepository#getOrInsert}
     * under a {@code FOR UPDATE} lock so a concurrent compactor merging the same bucket blocks
     * instead of racing (a lost-update would otherwise be possible: both merges read the same
     * pre-merge totals and the second write would silently overwrite the first).
     */
    private void mergeBucket(DSLContext context, BucketGroupKey key, Instant bucketDate, List<ExecutionStatistic> bucketRows) {
        State.Type state = State.Type.valueOf(key.state());
        ExecutionStatistic zero = new ExecutionStatistic(key.tenantId(), key.namespace(), key.flowId(), bucketDate, state, 0, 0, 0, 0, 0, 0, null, null, null);

        ExecutionStatistic existing = jdbcRepository.getOrInsert(
            context,
            () -> jdbcRepository.fetchOne(
                context.select(table().VALUE)
                    .from(table())
                    .where(table().KEY.eq(zero.uid()))
                    .forUpdate()
            ),
            () -> zero
        );

        long count = 0;
        long durationSum = 0;
        long durationMin = Long.MAX_VALUE;
        long durationMax = 0;
        long taskRunCount = 0;
        long taskRunsDurationSum = 0;
        Long taskRunsDurationMin = null;
        Long taskRunsDurationMax = null;
        // bucketRows is never empty: it's a group from Collectors.groupingBy over the non-empty
        // rawRows fetched in compactKey.
        for (ExecutionStatistic row : bucketRows) {
            count += row.count();
            durationSum += row.durationSumMs();
            durationMin = Math.min(durationMin, row.durationMinMs());
            durationMax = Math.max(durationMax, row.durationMaxMs());
            taskRunCount += row.taskRunCount();
            taskRunsDurationSum += row.taskRunsDurationSumMs();
            taskRunsDurationMin = minOf(taskRunsDurationMin, row.taskRunsDurationMinMs());
            taskRunsDurationMax = maxOf(taskRunsDurationMax, row.taskRunsDurationMaxMs());
        }

        ExecutionStatistic merged = new ExecutionStatistic(
            key.tenantId(),
            key.namespace(),
            key.flowId(),
            bucketDate,
            state,
            existing.count() + count,
            existing.durationSumMs() + durationSum,
            existing.count() == 0 ? durationMin : Math.min(existing.durationMinMs(), durationMin),
            existing.count() == 0 ? durationMax : Math.max(existing.durationMaxMs(), durationMax),
            existing.taskRunCount() + taskRunCount,
            existing.taskRunsDurationSumMs() + taskRunsDurationSum,
            minOf(existing.taskRunsDurationMinMs(), taskRunsDurationMin),
            maxOf(existing.taskRunsDurationMaxMs(), taskRunsDurationMax),
            null
        );

        jdbcRepository.persist(merged, context, jdbcRepository.persistFields(merged));
    }

    /**
     * Null-safe {@code min}: a null operand means "no task run contributed a value yet", not zero.
     * Package-private: also used by {@link AbstractJdbcExecutionStatisticsRepository} for the same
     * merge semantics on the read side, instead of a second hand-rolled copy.
     */
    static Long minOf(@Nullable Long a, @Nullable Long b) {
        if (a == null)
            return b;
        if (b == null)
            return a;
        return Math.min(a, b);
    }

    /**
     * Null-safe {@code max}: a null operand means "no task run contributed a value yet", not zero.
     * Package-private: also used by {@link AbstractJdbcExecutionStatisticsRepository} for the same
     * merge semantics on the read side, instead of a second hand-rolled copy.
     */
    static Long maxOf(@Nullable Long a, @Nullable Long b) {
        if (a == null)
            return b;
        if (b == null)
            return a;
        return Math.max(a, b);
    }

    private record BucketGroupKey(String tenantId, String namespace, String flowId, String state) {
    }
}
