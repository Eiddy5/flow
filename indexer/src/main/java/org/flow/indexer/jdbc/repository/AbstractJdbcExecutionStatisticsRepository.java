package org.flow.indexer.jdbc.repository;

import org.flow.core.jdbc.repository.AbstractJdbcCrudRepository;
import org.flow.core.jdbc.repository.AbstractJdbcRepository;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SelectConditionStep;
import org.jooq.impl.DSL;

import org.flow.gen.flow.Tables;
import org.flow.gen.flow.tables.ExecutionStatisticsTable;
import org.flow.core.models.QueryFilter;
import org.flow.core.models.executions.statistics.DailyExecutionStatistics;
import org.flow.core.models.executions.statistics.ExecutionStatistic;
import org.flow.core.models.flows.State;
import org.flow.core.repositories.ExecutionStatisticsRepositoryInterface;
import org.flow.core.utils.DateUtils;

import io.micronaut.core.annotation.Nullable;

/**
 * Base JDBC repository for the pre-aggregated {@code execution_statistics} table.
 * <p>
 * The table mixes raw rows (one per terminated execution, {@code count = 1}) with rows already
 * merged by the periodic compaction job ({@code count = N}); see {@link ExecutionStatistic} for
 * why this makes {@link #statistics} a uniform aggregation regardless of compaction state.
 * <p>
 */
public abstract class AbstractJdbcExecutionStatisticsRepository extends AbstractJdbcCrudRepository<ExecutionStatistic> implements ExecutionStatisticsRepositoryInterface {

    @Override
    protected ExecutionStatisticsTable table() {
        return jdbcRepository.getTable(Tables.EXECUTION_STATISTICS);
    }

    public AbstractJdbcExecutionStatisticsRepository(org.flow.core.jdbc.AbstractJdbcRepository<ExecutionStatistic> jdbcRepository) {
        super(jdbcRepository);
    }

    /** {@inheritDoc} **/
    @Override
    protected Condition defaultFilter(String tenantId) {
        return tenantCondition(tenantId).and(aclCondition(QueryFilter.Resource.EXECUTION));
    }

    /** {@inheritDoc} **/
    @Override
    protected Condition defaultFilter() {
        return DSL.trueCondition();
    }

    /** {@inheritDoc} **/
    @Override
    public List<DailyExecutionStatistics> statistics(
        String tenantId,
        @Nullable String namespace,
        @Nullable String flowId,
        Instant startDate,
        Instant endDate,
        DateUtils.GroupType groupBy) {
        DateUtils.GroupType groupByType = groupBy != null ? groupBy : DateUtils.groupByType(Duration.between(startDate, endDate));

        List<ExecutionStatistic> rows = this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                DSLContext context = DSL.using(configuration);

                SelectConditionStep<?> select = context
                    .select(table().VALUE)
                    .from(table())
                    .where(this.defaultFilter(tenantId))
                    .and(table().DATE.greaterOrEqual(bindableDate(startDate)))
                    .and(table().DATE.lessOrEqual(bindableDate(endDate)));

                if (namespace != null) {
                    select = select.and(table().NAMESPACE.eq(namespace));
                }

                if (flowId != null) {
                    select = select.and(table().FLOW_ID.eq(flowId));
                }

                return this.jdbcRepository.fetch(select);
            });

        return mapStatistics(rows, startDate, endDate, groupByType);
    }

    /** {@inheritDoc} **/
    @Override
    public List<DailyExecutionStatistics> statisticsForAllTenants(
        Instant startDate,
        Instant endDate,
        DateUtils.GroupType groupBy) {
        DateUtils.GroupType groupByType = groupBy != null ? groupBy : DateUtils.groupByType(Duration.between(startDate, endDate));

        List<ExecutionStatistic> rows = this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                DSLContext context = DSL.using(configuration);

                SelectConditionStep<?> select = context
                    .select(table().VALUE)
                    .from(table())
                    .where(table().DATE.greaterOrEqual(bindableDate(startDate)))
                    .and(table().DATE.lessOrEqual(bindableDate(endDate)));

                return this.jdbcRepository.fetch(select);
            });

        return mapStatistics(rows, startDate, endDate, groupByType);
    }

    private List<DailyExecutionStatistics> mapStatistics(List<ExecutionStatistic> rows, Instant startDate, Instant endDate, DateUtils.GroupType groupByType) {
        Map<Instant, List<ExecutionStatistic>> byBucket = rows.stream()
            .collect(Collectors.groupingBy(row -> truncateToBucket(row.date(), groupByType)));

        List<DailyExecutionStatistics> results = byBucket.entrySet().stream()
            .map(entry -> dailyExecutionStatisticsMap(entry.getKey(), entry.getValue(), groupByType.val()))
            .toList();

        return fillDate(results, truncateToBucket(startDate, groupByType), truncateToBucket(endDate, groupByType), groupByType);
    }

    /** Converts an instant to a timezone-aware PostgreSQL timestamp binding. */
    static java.time.OffsetDateTime bindableDate(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /**
     * Tenant-null-safe equality condition on {@code tenant_id}.
     */
    private Condition tenantCondition(String tenantId) {
        return tenantId == null ? table().TENANT_ID.isNull() : table().TENANT_ID.eq(tenantId);
    }

    /**
     * Truncates an instant to the start of its bucket for the given group type. Operates directly
     * on the real (deserialized) {@link Instant}, in UTC — no SQL-side date-part extraction is
     * involved, so there is no risk of the system-default-zone reconstruction bug that a
     * digit-extraction approach (year/month/day/hour/minute columns) would be exposed to.
     */
    private static Instant truncateToBucket(Instant instant, DateUtils.GroupType groupType) {
        return switch (groupType) {
            case MINUTE -> instant.truncatedTo(ChronoUnit.MINUTES);
            case HOUR -> instant.truncatedTo(ChronoUnit.HOURS);
            case DAY -> instant.truncatedTo(ChronoUnit.DAYS);
            case WEEK -> instant.truncatedTo(ChronoUnit.DAYS)
                .atZone(ZoneOffset.UTC)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .toInstant();
            case MONTH -> instant.atZone(ZoneOffset.UTC).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).toInstant();
        };
    }

    /**
     * Fills gaps in {@code results} so every bucket in {@code [startDate, endDate]} is present,
     * even when it has no matching rows.
     * <p>
     * 执行仓储的日期补齐逻辑 and {@link AbstractJdbcMetricRepository}'s
     * private {@code fillDate} reimplement the same gap-filling idea, but anchor to
     * {@code ZoneId.systemDefault()} instead of UTC — self-consistent with their own
     * {@code AbstractJdbcRepository#getDate} bucket-key extraction (which has the same
     * system-default-zone bug), but wrong in absolute terms on non-UTC hosts. Not consolidated
     * here: fixing it properly means fixing {@code getDate()} too, which is shared by those two
     * already-shipped read paths and out of scope for this feature.
     */
    private static List<DailyExecutionStatistics> fillDate(List<DailyExecutionStatistics> results, Instant startDate, Instant endDate, DateUtils.GroupType groupByType) {
        ChronoUnit unit = switch (groupByType) {
            case MONTH -> ChronoUnit.MONTHS;
            case WEEK -> ChronoUnit.WEEKS;
            case DAY -> ChronoUnit.DAYS;
            case HOUR -> ChronoUnit.HOURS;
            case MINUTE -> ChronoUnit.MINUTES;
        };

        Map<Instant, DailyExecutionStatistics> byBucket = results.stream()
            .collect(Collectors.toMap(DailyExecutionStatistics::getStartDate, r -> r));

        List<DailyExecutionStatistics> filledResult = new ArrayList<>();
        // MONTH/WEEK are calendar (not fixed-duration) units, so advancing requires a zoned
        // representation; Instant.plus(...) only supports fixed-duration units.
        ZonedDateTime current = startDate.atZone(ZoneOffset.UTC);
        ZonedDateTime boundary = endDate.atZone(ZoneOffset.UTC).plus(1, unit);

        while (current.isBefore(boundary)) {
            Instant bucket = current.toInstant();
            filledResult.add(
                byBucket.getOrDefault(
                    bucket, DailyExecutionStatistics.builder()
                        .startDate(bucket)
                        .groupBy(groupByType.val())
                        .duration(DailyExecutionStatistics.Duration.builder().build())
                        .taskRunsDuration(DailyExecutionStatistics.Duration.builder().build())
                        .build()
                )
            );
            current = current.plus(1, unit);
        }

        return filledResult;
    }

    private static DailyExecutionStatistics dailyExecutionStatisticsMap(Instant bucket, List<ExecutionStatistic> rows, String groupByType) {
        long count = 0;
        long durationSum = 0;
        long durationMin = Long.MAX_VALUE;
        long durationMax = 0;
        long taskRunCount = 0;
        long taskRunsDurationSum = 0;
        Long taskRunsDurationMin = null;
        Long taskRunsDurationMax = null;
        Map<State.Type, Long> countsByState = new EnumMap<>(State.Type.class);

        // rows is never empty: it's a group from Collectors.groupingBy over the non-empty rows
        // fetched by statistics(), so durationMin/durationMax always get a real value below.
        for (ExecutionStatistic row : rows) {
            count += row.count();
            durationSum += row.durationSumMs();
            durationMin = Math.min(durationMin, row.durationMinMs());
            durationMax = Math.max(durationMax, row.durationMaxMs());
            taskRunCount += row.taskRunCount();
            taskRunsDurationSum += row.taskRunsDurationSumMs();
            taskRunsDurationMin = ExecutionStatisticsCompactor.minOf(taskRunsDurationMin, row.taskRunsDurationMinMs());
            taskRunsDurationMax = ExecutionStatisticsCompactor.maxOf(taskRunsDurationMax, row.taskRunsDurationMaxMs());
            countsByState.merge(row.state(), row.count(), Long::sum);
        }

        DailyExecutionStatistics build = DailyExecutionStatistics.builder()
            .startDate(bucket)
            .groupBy(groupByType)
            .duration(
                DailyExecutionStatistics.Duration.builder()
                    .avg(Duration.ofMillis(count == 0 ? 0 : durationSum / count))
                    .min(Duration.ofMillis(durationMin))
                    .max(Duration.ofMillis(durationMax))
                    .sum(Duration.ofMillis(durationSum))
                    .count(count)
                    .build()
            )
            .taskRunsDuration(
                DailyExecutionStatistics.Duration.builder()
                    .avg(Duration.ofMillis(taskRunCount == 0 ? 0 : taskRunsDurationSum / taskRunCount))
                    .min(taskRunsDurationMin == null ? null : Duration.ofMillis(taskRunsDurationMin))
                    .max(taskRunsDurationMax == null ? null : Duration.ofMillis(taskRunsDurationMax))
                    .sum(Duration.ofMillis(taskRunsDurationSum))
                    .count(taskRunCount)
                    .build()
            )
            .build();

        build.getExecutionCounts().putAll(countsByState);

        return build;
    }
}
