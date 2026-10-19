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
import java.util.*;
import java.util.function.Function;

import org.jooq.*;
import org.jooq.impl.DSL;

import org.flow.gen.flow.Tables;
import org.flow.gen.flow.tables.MetricsTable;
import org.flow.core.models.QueryFilter;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.ExecutionKind;
import org.flow.core.models.executions.MetricEntry;
import org.flow.core.models.executions.metrics.MetricAggregation;
import org.flow.core.models.executions.metrics.MetricAggregations;
import org.flow.core.repositories.ArrayListTotal;
import org.flow.core.repositories.MetricRepositoryInterface;
import org.flow.core.utils.DateUtils;

import io.micrometer.common.lang.Nullable;
import io.micronaut.data.model.Pageable;

public abstract class AbstractJdbcMetricRepository extends AbstractJdbcCrudRepository<MetricEntry> implements MetricRepositoryInterface {

    @Override
    protected MetricsTable table() {
        return jdbcRepository.getTable(Tables.METRICS);
    }
    private Condition normalKindCondition() {
        return table().EXECUTION_KIND.isNull().or(table().EXECUTION_KIND.eq(ExecutionKind.NORMAL.name()));
    }

    public AbstractJdbcMetricRepository(org.flow.core.jdbc.AbstractJdbcRepository<MetricEntry> jdbcRepository) {
        super(jdbcRepository);

    }

    @Override
    public ArrayListTotal<MetricEntry> findByExecutionId(String tenantId, String executionId, Pageable pageable) {
        return this.findPage(
            pageable,
            tenantId,
            table().EXECUTION_ID.eq(executionId)
        );
    }

    @Override
    public ArrayListTotal<MetricEntry> findByExecutionIdAndTaskId(String tenantId, String executionId, String taskId, Pageable pageable) {
        return this.findPage(
            pageable,
            tenantId,
            table().EXECUTION_ID.eq(executionId)
                .and(table().TASK_ID.eq(taskId))
        );
    }

    @Override
    public ArrayListTotal<MetricEntry> findByExecutionIdAndTaskRunId(String tenantId, String executionId, String taskRunId, Pageable pageable) {
        return this.findPage(
            pageable,
            tenantId,
            table().EXECUTION_ID.eq(executionId)
                .and(table().TASKRUN_ID.eq(taskRunId))
        );
    }

    @Override
    public List<String> flowMetrics(
        String tenantId,
        String namespace,
        String flowId) {
        return this.queryDistinct(
            tenantId,
            table().FLOW_ID.eq(flowId)
                .and(table().NAMESPACE.eq(namespace))
                .and(normalKindCondition()),
            table().METRIC_NAME
        );
    }

    @Override
    public List<String> taskMetrics(
        String tenantId,
        String namespace,
        String flowId,
        String taskId) {
        return this.queryDistinct(
            tenantId,
            table().FLOW_ID.eq(flowId)
                .and(table().NAMESPACE.eq(namespace))
                .and(table().TASK_ID.eq(taskId))
                .and(normalKindCondition()),
            table().METRIC_NAME
        );
    }

    @Override
    public List<String> tasksWithMetrics(
        String tenantId,
        String namespace,
        String flowId) {
        return this.queryDistinct(
            tenantId,
            table().FLOW_ID.eq(flowId)
                .and(table().NAMESPACE.eq(namespace))
                .and(normalKindCondition()),
            table().TASK_ID
        );
    }

    @Override
    public MetricAggregations aggregateByFlowId(
        String tenantId,
        String namespace,
        String flowId,
        @Nullable String taskId,
        String metric,
        ZonedDateTime startDate,
        ZonedDateTime endDate,
        String aggregation) {
        Condition conditions = table().FLOW_ID.eq(flowId)
            .and(table().NAMESPACE.eq(namespace))
            .and(table().METRIC_NAME.eq(metric))
            .and(normalKindCondition());
        if (taskId != null) {
            conditions = conditions.and(table().TASK_ID.eq(taskId));
        }
        return MetricAggregations
            .builder()
            .aggregations(
                this.aggregate(
                    tenantId,
                    conditions,
                    startDate,
                    endDate,
                    aggregation
                )
            )
            .groupBy(DateUtils.groupByType(Duration.between(startDate, endDate)).val())
            .build();
    }

    @Override
    public Integer purge(Execution execution) {
        return purge(DSL.noCondition(), table().EXECUTION_ID.eq(execution.getId()));
    }

    @Override
    public Integer purge(List<Execution> executions) {
        return purge(DSL.noCondition(), table().EXECUTION_ID.in(executions.stream().map(Execution::getId).toList()));
    }

    @Override
    protected Condition defaultFilter(String tenantId) {
        return buildTenantCondition(tenantId).and(aclCondition(QueryFilter.Resource.EXECUTION));
    }

    @Override
    protected Condition defaultFilter() {
        return DSL.trueCondition();
    }

    private List<String> queryDistinct(String tenantId, Condition condition, Field<String> field) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                DSLContext context = DSL.using(configuration);
                SelectConditionStep<Record1<String>> select = context
                    .selectDistinct(field)
                    .from(table())
                    .where(this.defaultFilter(tenantId));

                select = select.and(condition);

                return select.fetch().map(record -> record.get(field));
            });
    }

    private List<MetricAggregation> aggregate(
        String tenantId,
        Condition condition,
        ZonedDateTime startDate,
        ZonedDateTime endDate,
        String aggregation) {
        List<Field<?>> dateFields = new ArrayList<>(groupByFields(Duration.between(startDate, endDate), true));
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                var select = DSL
                    .using(configuration)
                    .select(dateFields)
                    .select(
                        table().METRIC_NAME,
                        aggregate(aggregation)
                    )
                    .from(table())
                    .where(this.defaultFilter(tenantId));

                select = select.and(condition);

                if (startDate != null) {
                    select = select.and(table().TIMESTAMP.greaterOrEqual(startDate.toOffsetDateTime()));
                }

                if (endDate != null) {
                    select = select.and(table().TIMESTAMP.lessOrEqual(endDate.toOffsetDateTime()));
                }

                dateFields.add(table().METRIC_NAME);

                List<Field<?>> groupByFields = new ArrayList<>(groupByFields(Duration.between(startDate, endDate)));
                groupByFields.add(table().METRIC_NAME);
                var selectGroup = select.groupBy(groupByFields);

                List<MetricAggregation> result = this.jdbcRepository
                    .fetchMetricStat(selectGroup, DateUtils.groupByType(Duration.between(startDate, endDate)).val());

                List<MetricAggregation> fillResult = fillDate(result, startDate, endDate);

                return fillResult;
            });
    }

    private Field<?> aggregate(String aggregation) {
        return switch (aggregation) {
            case "avg" -> DSL.avg(table().METRIC_VALUE).as("metric_value");
            case "sum" -> DSL.sum(table().METRIC_VALUE).as("metric_value");
            case "min" -> DSL.min(table().METRIC_VALUE).as("metric_value");
            case "max" -> DSL.max(table().METRIC_VALUE).as("metric_value");
            default -> throw new IllegalArgumentException("Invalid aggregation: " + aggregation);
        };
    }

    private List<MetricAggregation> fillDate(List<MetricAggregation> result, ZonedDateTime startDate, ZonedDateTime endDate) {
        DateUtils.GroupType groupByType = DateUtils.groupByType(Duration.between(startDate, endDate));

        return switch (groupByType) {
            case MONTH -> fillDate(result, startDate, endDate, ChronoUnit.MONTHS);
            case WEEK -> fillDate(result, startDate, endDate, ChronoUnit.WEEKS);
            case DAY -> fillDate(result, startDate, endDate, ChronoUnit.DAYS);
            case HOUR -> fillDate(result, startDate, endDate, ChronoUnit.HOURS);
            case MINUTE -> fillDate(result, startDate, endDate, ChronoUnit.MINUTES);
        };
    }

    private List<MetricAggregation> fillDate(
        List<MetricAggregation> result,
        ZonedDateTime startDate,
        ZonedDateTime endDate,
        ChronoUnit unit) {
        List<MetricAggregation> filledResult = new ArrayList<>();
        // Floored to the bucket boundary so the generated buckets line up with the calendar-floored
        // instants AbstractJdbcRepository#getDate reassembles from the SQL year/month/week/day parts;
        // otherwise the last bucket walked from an unfloored startDate can fall short of endDate and
        // silently drop the most recent data point.
        ZonedDateTime currentDate = truncateToBucket(startDate, unit);
        while (currentDate.isBefore(endDate)) {
            Instant bucketInstant = currentDate.toInstant();
            MetricAggregation metricStat = result.stream()
                .filter(metric -> metric.date.equals(bucketInstant))
                .findFirst()
                .orElse(MetricAggregation.builder().date(bucketInstant).value(0.0).build());

            filledResult.add(metricStat);
            currentDate = currentDate.plus(1, unit);
        }

        return filledResult;
    }

    private static ZonedDateTime truncateToBucket(ZonedDateTime date, ChronoUnit unit) {
        ZonedDateTime utc = date.withZoneSameInstant(ZoneOffset.UTC);
        // MONTHS/WEEKS aren't supported by truncatedTo, and must match AbstractJdbcRepository#getDate's
        // calendar-floored buckets (first-of-month, Monday-of-week).
        return switch (unit) {
            case MONTHS -> utc.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
            case WEEKS -> utc.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).truncatedTo(ChronoUnit.DAYS);
            default -> utc.truncatedTo(unit);
        };
    }

    @Override
    public Function<String, String> sortMapping() throws IllegalArgumentException {
        Map<String, String> mapper = Map.of(
            "namespace", table().NAMESPACE.getName(),
            "flowId", table().FLOW_ID.getName(),
            "taskId", table().TASK_ID.getName(),
            "executionId", table().EXECUTION_ID.getName(),
            "taskRunId", table().TASKRUN_ID.getName(),
            "name", table().METRIC_NAME.getName(),
            "timestamp", table().TIMESTAMP.getName(),
            "value", table().METRIC_VALUE.getName()
        );

        return mapper::get;
    }

}
