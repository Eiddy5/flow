package org.flow.jdbc.repository;

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
    private static final Condition NORMAL_KIND_CONDITION = field("execution_kind").isNull().or(field("execution_kind").eq(ExecutionKind.NORMAL.name()));

    public AbstractJdbcMetricRepository(org.flow.jdbc.AbstractJdbcRepository<MetricEntry> jdbcRepository) {
        super(jdbcRepository);

    }

    @Override
    public ArrayListTotal<MetricEntry> findByExecutionId(String tenantId, String executionId, Pageable pageable) {
        return this.findPage(
            pageable,
            tenantId,
            field("execution_id").eq(executionId)
        );
    }

    @Override
    public ArrayListTotal<MetricEntry> findByExecutionIdAndTaskId(String tenantId, String executionId, String taskId, Pageable pageable) {
        return this.findPage(
            pageable,
            tenantId,
            field("execution_id").eq(executionId)
                .and(field("task_id").eq(taskId))
        );
    }

    @Override
    public ArrayListTotal<MetricEntry> findByExecutionIdAndTaskRunId(String tenantId, String executionId, String taskRunId, Pageable pageable) {
        return this.findPage(
            pageable,
            tenantId,
            field("execution_id").eq(executionId)
                .and(field("taskrun_id").eq(taskRunId))
        );
    }

    @Override
    public List<String> flowMetrics(
        String tenantId,
        String namespace,
        String flowId) {
        return this.queryDistinct(
            tenantId,
            field("flow_id").eq(flowId)
                .and(field("namespace").eq(namespace))
                .and(NORMAL_KIND_CONDITION),
            "metric_name"
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
            field("flow_id").eq(flowId)
                .and(field("namespace").eq(namespace))
                .and(field("task_id").eq(taskId))
                .and(NORMAL_KIND_CONDITION),
            "metric_name"
        );
    }

    @Override
    public List<String> tasksWithMetrics(
        String tenantId,
        String namespace,
        String flowId) {
        return this.queryDistinct(
            tenantId,
            field("flow_id").eq(flowId)
                .and(field("namespace").eq(namespace))
                .and(NORMAL_KIND_CONDITION),
            "task_id"
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
        Condition conditions = field("flow_id").eq(flowId)
            .and(field("namespace").eq(namespace))
            .and(field("metric_name").eq(metric))
            .and(NORMAL_KIND_CONDITION);
        if (taskId != null) {
            conditions = conditions.and(field("task_id").eq(taskId));
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
        return purge(DSL.noCondition(), field("execution_id", String.class).eq(execution.getId()));
    }

    @Override
    public Integer purge(List<Execution> executions) {
        return purge(DSL.noCondition(), field("execution_id", String.class).in(executions.stream().map(Execution::getId).toList()));
    }

    @Override
    protected Condition defaultFilter(String tenantId) {
        return buildTenantCondition(tenantId).and(aclCondition(QueryFilter.Resource.EXECUTION));
    }

    @Override
    protected Condition defaultFilter() {
        return DSL.trueCondition();
    }

    private List<String> queryDistinct(String tenantId, Condition condition, String field) {
        return this.jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                DSLContext context = DSL.using(configuration);
                SelectConditionStep<Record1<Object>> select = context
                    .selectDistinct(field(field))
                    .from(this.jdbcRepository.getTable())
                    .where(this.defaultFilter(tenantId));

                select = select.and(condition);

                return select.fetch().map(record -> record.get(field, String.class));
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
                        field("metric_name"),
                        aggregate(aggregation)
                    )
                    .from(this.jdbcRepository.getTable())
                    .where(this.defaultFilter(tenantId));

                select = select.and(condition);

                if (startDate != null) {
                    select = select.and(field("timestamp").greaterOrEqual(startDate.toOffsetDateTime()));
                }

                if (endDate != null) {
                    select = select.and(field("timestamp").lessOrEqual(endDate.toOffsetDateTime()));
                }

                dateFields.add(field("metric_name"));

                List<Field<?>> groupByFields = new ArrayList<>(groupByFields(Duration.between(startDate, endDate)));
                groupByFields.add(field("metric_name"));
                var selectGroup = select.groupBy(groupByFields);

                List<MetricAggregation> result = this.jdbcRepository
                    .fetchMetricStat(selectGroup, DateUtils.groupByType(Duration.between(startDate, endDate)).val());

                List<MetricAggregation> fillResult = fillDate(result, startDate, endDate);

                return fillResult;
            });
    }

    private Field<?> aggregate(String aggregation) {
        return switch (aggregation) {
            case "avg" -> DSL.avg(field("metric_value", Double.class)).as("metric_value");
            case "sum" -> DSL.sum(field("metric_value", Double.class)).as("metric_value");
            case "min" -> DSL.min(field("metric_value", Double.class)).as("metric_value");
            case "max" -> DSL.max(field("metric_value", Double.class)).as("metric_value");
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
            "namespace", "namespace",
            "flowId", "flow_id",
            "taskId", "task_id",
            "executionId", "execution_id",
            "taskRunId", "taskrun_id",
            "name", "metric_name",
            "timestamp", "timestamp",
            "value", "metric_value"
        );

        return mapper::get;
    }

}
