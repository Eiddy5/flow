package org.flow.core.jdbc;

import org.flow.jdbc.JdbcTableConfig;
import org.flow.jdbc.JooqDSLContextWrapper;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.jooq.*;
import org.jooq.Record;
import org.jooq.impl.DSL;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.flow.gen.flow.Tables;
import org.flow.core.exceptions.DeserializationException;
import org.flow.core.models.HasUID;
import org.flow.core.models.executions.metrics.MetricAggregation;
import org.flow.core.repositories.ArrayListTotal;
import org.flow.core.repositories.NonSortableFields;
import org.flow.core.serializers.JacksonMapper;
import org.flow.core.utils.IdUtils;

import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.Sort;
import io.micronaut.data.model.Sort.Order;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;

import static org.flow.core.utils.CaseUtils.camelToSnake;

public abstract class AbstractJdbcRepository<T> {
    protected static final ObjectMapper MAPPER = JacksonMapper.ofJson();

    /**
     * Columns never exposed as a sort key, on top of whatever the metadata check in {@link #sort} rejects because
     * it does not exist. Derived from {@link NonSortableFields#DEFAULT}, converted to the snake_case column names
     * this backend compares against, so the JDBC and Elasticsearch backends refuse the same sort fields.
     */
    private static final Set<String> NON_SORTABLE_COLUMNS = NonSortableFields.DEFAULT.stream()
        .map(field -> camelToSnake(field).toLowerCase())
        .collect(Collectors.toUnmodifiableSet());

    protected final Class<T> cls;

    @Setter
    protected Function<Record, T> deserializer;

    @Getter
    protected final JooqDSLContextWrapper dslContextWrapper;

    @Getter
    protected Table<Record> table;

    @SuppressWarnings("unchecked")
    public AbstractJdbcRepository(
        JdbcTableConfig tableConfig,
        JooqDSLContextWrapper dslContextWrapper) {
        this.cls = (Class<T>) tableConfig.cls();
        this.dslContextWrapper = dslContextWrapper;
        this.table = tableConfig.jooqTable();
    }

    /** Returns the configured instance of the requested generated table type. */
    @SuppressWarnings("unchecked")
    public <TABLE extends Table<?>> TABLE getTable(TABLE generatedTable) {
        if (!generatedTable.getClass().isInstance(table)) {
            throw new IllegalArgumentException("Repository table is not " + generatedTable.getName());
        }
        return (TABLE) table;
    }

    public Field<String> keyField() {
        return java.util.Objects.requireNonNull(table.field(Tables.FLOWS.KEY));
    }

    public Field<JSONB> valueField() {
        return java.util.Objects.requireNonNull(table.field(Tables.FLOWS.VALUE));
    }

    abstract public Condition fullTextCondition(List<? extends Field<?>> fields, String query);

    public String key(T entity) {
        String key = entity instanceof HasUID hasUID ? hasUID.uid() : null;

        if (key != null) {
            return key;
        }

        return IdUtils.create();
    }

    @SneakyThrows
    public Map<Field<?>, Object> persistFields(T entity) {
        Map<Field<?>, Object> fields = HashMap.newHashMap(1);
        fields.put(valueField(), JSONB.valueOf(MAPPER.writeValueAsString(entity)));
        return fields;
    }

    /**
     * Fetches an entity using {@code fetcher}, or inserts a default one (via {@code defaultEntity}) if absent, then re-fetches.
     * The {@code fetcher} should use {@code FOR UPDATE} so the returned entity is locked within the caller's transaction.
     */
    public T getOrInsert(DSLContext dslContext, Supplier<Optional<T>> fetcher, Supplier<T> defaultEntity) {
        return fetcher.get().orElseGet(() ->
        {
            T entity = defaultEntity.get();
            dslContext.insertInto(this.getTable())
                .set(keyField(), this.key(entity))
                .set(this.persistFields(entity))
                .onDuplicateKeyIgnore()
                .execute();
            // Re-fetch with the caller's FOR UPDATE lock after a concurrent insert.
            return fetcher.get().orElseThrow();
        });
    }

    public int count(Condition condition) {
        return getDslContextWrapper()
            .transactionResult(
                configuration -> DSL
                    .using(configuration)
                    .selectCount()
                    .from(getTable())
                    .where(condition)
                    .fetchOne(0, Integer.class)
            );
    }

    /**
     * Do an insert or update on the table (upsert).
     * This is convenient to be fault-tolerant of possible conflict but is less performant than an UPDATE is we're sure the entity exists.
     *
     * @see #persist(T, Map)
     * @see #persist(T, DSLContext, Map)
     * @see #update(T)
     * @see #update(T, Map)
     * @see #update(T, DSLContext, Map)
     */
    public void persist(T entity) {
        this.persist(entity, null);
    }

    /**
     * Do an insert or update on the table (upsert).
     * This is convenient to be fault-tolerant of possible conflict but is less performant than an UPDATE is we're sure the entity exists.
     *
     * @see #persist(T)
     * @see #persist(T, DSLContext, Map)
     * @see #update(T)
     * @see #update(T, Map)
     * @see #update(T, DSLContext, Map)
     */
    public void persist(T entity, Map<Field<?>, Object> fields) {
        dslContextWrapper.transaction(
            configuration -> this.persist(entity, DSL.using(configuration), fields)
        );
    }

    /**
     * Do an insert or update on the table (upsert).
     * This is convenient to be fault-tolerant of possible conflict but is less performant than an UPDATE is we're sure the entity exists.
     *
     * @see #persist(T)
     * @see #persist(T, Map)
     * @see #update(T)
     * @see #update(T, Map)
     * @see #update(T, DSLContext, Map)
     */
    public void persist(T entity, DSLContext dslContext, Map<Field<?>, Object> fields) {
        Map<Field<?>, Object> finalFields = fields == null ? this.persistFields(entity) : fields;

        dslContext
            .insertInto(table)
            .set(keyField(), key(entity))
            .set(finalFields)
            .onDuplicateKeyUpdate()
            .set(excluded(finalFields))
            .execute();
    }

    /**
     * Turns a column-to-value map into a column-to-{@code EXCLUDED}/{@code VALUES(...)} map, so the
     * update clause of an upsert re-references the row already bound by the insert clause instead of
     * binding the (potentially large) value a second time. jOOQ renders this per-dialect: the
     * {@code EXCLUDED} pseudo-table on PostgreSQL.
     */
    protected static Map<Field<?>, Object> excluded(Map<Field<?>, Object> fields) {
        Map<Field<?>, Object> excluded = HashMap.newHashMap(fields.size());
        fields.keySet().forEach(field -> excluded.put(field, DSL.excluded(field)));
        return excluded;
    }

    /**
     * Update the entity.
     * For a safer upsert approach see the corresponding <code>persist()</code> methods
     *
     * @see #update(Object, Map)
     * @see #update(Object, DSLContext, Map)
     * @see #persist(Object)
     * @see #persist(Object, Map)
     * @see #persist(Object, DSLContext, Map)
     */
    public void update(T entity) {
        this.persist(entity, null);
    }

    /**
     * Update the entity.
     * For a safer upsert approach see the corresponding <code>persist()</code> methods
     *
     * @see #update(Object)
     * @see #update(Object, DSLContext, Map)
     * @see #persist(Object)
     * @see #persist(Object, Map)
     * @see #persist(Object, DSLContext, Map)
     */
    public void update(T entity, Map<Field<?>, Object> fields) {
        dslContextWrapper.transaction(
            configuration -> this.persist(entity, DSL.using(configuration), fields)
        );
    }

    /**
     * Update the entity.
     * For a safer upsert approach see the corresponding <code>persist()</code> methods
     *
     * @see #update(Object)
     * @see #update(Object, Map)
     * @see #persist(Object)
     * @see #persist(Object, Map)
     * @see #persist(Object, DSLContext, Map)
     */
    public void update(T entity, DSLContext dslContext, Map<Field<?>, Object> fields) {
        Map<Field<?>, Object> finalFields = fields == null ? this.persistFields(entity) : fields;

        dslContext
            .update(table)
            .set(finalFields)
            .where(keyField().eq(key(entity)))
            .execute();
    }

    public int persistBatch(List<T> items) {
        return dslContextWrapper.transactionResult(configuration ->
        {
            DSLContext dslContext = DSL.using(configuration);
            var inserts = items.stream()
                .map(item -> buildInsertRequest(item, this.persistFields(item), dslContext))
                .toList();

            return Arrays.stream(dslContext.batch(inserts).execute()).sum();
        });
    }

    public int persistBatch(Map<T, Map<Field<?>, Object>> itemWithFields) {
        return dslContextWrapper.transactionResult(configuration ->
        {
            DSLContext dslContext = DSL.using(configuration);
            var inserts = itemWithFields.entrySet()
                .stream().map(entry -> buildInsertRequest(entry.getKey(), entry.getValue(), dslContext))
                .toList();

            return Arrays.stream(dslContext.batch(inserts).execute()).sum();
        });
    }

    protected InsertOnDuplicateSetMoreStep<Record> buildInsertRequest(T entity, Map<Field<?>, Object> fields,
        DSLContext dslContext) {

        return dslContext
            .insertInto(table)
            .set(keyField(), key(entity))
            .set(fields)
            .onDuplicateKeyUpdate()
            .set(excluded(fields));
    }

    public int delete(T entity) {
        return dslContextWrapper.transactionResult(configuration ->
        {
            return this.delete(DSL.using(configuration), entity);
        });
    }

    public int delete(DSLContext dslContext, T entity) {
        DeleteConditionStep<Record> key = dslContext
            .delete(table)
            .where(keyField().eq(key(entity)));

        return key.execute();
    }

    public <R extends Record> T map(R record) {
        if (deserializer != null) {
            return deserializer.apply(record);
        } else {
            return this.deserialize(record.get(valueField()).data());
        }
    }

    public <R extends Record> MetricAggregation mapMetricAggregation(R record, String groupByType) {
        Instant date = getDate(record, groupByType);
        return MetricAggregation
            .builder()
            .name(record.get(Tables.METRICS.METRIC_NAME))
            .value(record.get(Tables.METRICS.METRIC_VALUE, Double.class))
            .date(date)
            .build();

    }

    /**
     * Reassembles an {@link Instant} from the SQL-extracted date parts (year/month/day/hour/minute)
     * produced by {@code AbstractJdbcRepository#groupByFields}.
     * <p>
     * Those parts are UTC wall-clock values, normalised via {@code groupByTimestampField}, so they must
     * be reassembled in UTC. Using the JVM default zone here would shift every bucket by the local
     * UTC offset on a non-UTC host.
     */
    public <R extends Record> Instant getDate(R record, String groupByType) {
        List<String> fields = Arrays.stream(record.fields()).map(Field::getName).toList();
        Integer minute = fields.contains("minute") ? record.get("minute", Integer.class) : 0;
        Integer hour = fields.contains("hour") ? record.get("hour", Integer.class) : 0;
        Integer day = fields.contains("day") ? record.get("day", Integer.class) : 0;
        Integer week = fields.contains("week") ? record.get("week", Integer.class) : 0;
        Integer month = fields.contains("month") ? record.get("month", Integer.class) : 0;
        Integer year = fields.contains("year") ? record.get("year", Integer.class) : 0;

        switch (groupByType) {
            case "minute" -> {
                return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.UTC).toInstant();
            }
            case "hour" -> {
                return ZonedDateTime.of(year, month, day, hour, 0, 0, 0, ZoneOffset.UTC).toInstant();
            }
            case "day" -> {
                return ZonedDateTime.of(year, month, day, 0, 0, 0, 0, ZoneOffset.UTC).toInstant();
            }
            case "week" -> {
                // week * 7 can fall outside the 1..365/366 day-of-year range (e.g. week 53 -> 371, or
                // week 0 returned by some SQL WEEK() modes), which would make ofYearDay throw, so clamp it.
                int maxDayOfYear = LocalDate.of(year, 12, 31).getDayOfYear();
                int dayOfYear = Math.min(Math.max(week * 7, 1), maxDayOfYear);
                LocalDate weekDate = LocalDate.ofYearDay(year, dayOfYear).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                return weekDate.atStartOfDay(ZoneOffset.UTC).toInstant();
            }
            case "month" -> {
                return ZonedDateTime.of(year, month, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant();
            }
            default -> throw new IllegalArgumentException("Invalid groupByType: " + groupByType);
        }
    }

    public T deserialize(String record) {
        try {
            return MAPPER.readValue(record, cls);
        } catch (IOException e) {
            throw new DeserializationException(e, record);
        }
    }

    public <R extends Record> Optional<T> fetchOne(Select<R> select) {
        return Optional.ofNullable(select.fetchAny())
            .map(this::map);
    }

    public <R extends Record> List<T> fetch(Select<R> select) {
        return select.fetch().map(this::map);
    }

    public List<MetricAggregation> fetchMetricStat(Select<Record> select, String groupByType) {
        return select.fetch().map(e -> this.mapMetricAggregation(e, groupByType));
    }

    abstract public <R extends Record, E> ArrayListTotal<E> fetchPage(DSLContext context, SelectConditionStep<R> select, Pageable pageable, RecordMapper<R, E> mapper);

    public <R extends Record> ArrayListTotal<T> fetchPage(DSLContext context, SelectConditionStep<R> select, Pageable pageable) {
        return this.fetchPage(context, select, pageable, this::map);
    }

    @SuppressWarnings("unchecked")
    public <R extends Record> Select<R> buildQuery(DSLContext context, SelectConditionStep<R> select, String orderField) {
        return (Select<R>) context.select(DSL.asterisk())
            .from(
                this
                    .sort(select, Pageable.from(Sort.of(Order.asc(orderField))))
                    .asTable("page")
            )
            .where(DSL.noCondition());
    }

    public <R extends Record> SelectConditionStep<R> sort(SelectConditionStep<R> select, Pageable pageable) {
        if (pageable != null && pageable.getSort().isSorted()) {
            Map<String, Field<?>> sortableColumns = this.sortableColumns();

            pageable
                .getSort()
                .getOrderBy()
                .forEach(order ->
                {
                    String property = order.getProperty();
                    if (property == null || property.isBlank()) {
                        throw new IllegalArgumentException("Invalid sort field");
                    }

                    Field<?> column = sortableColumns.get(camelToSnake(property).toLowerCase());
                    if (column == null) {
                        column = sortableColumns.get(property.toLowerCase());
                    }
                    if (column == null) {
                        throw new IllegalArgumentException("The sort field '%s' does not exist on this resource.".formatted(property));
                    }

                    select.orderBy(order.getDirection() == Sort.Order.Direction.ASC ? column.asc().nullsFirst() : column.desc().nullsLast());
                });
        }

        return select;
    }

    /**
     * The columns of {@link #table} that a client may sort on, keyed by their lowercased name, excluding {@link #NON_SORTABLE_COLUMNS}.
     */
    private Map<String, Field<?>> sortableColumns() {
        Map<String, Field<?>> columns = new HashMap<>();
        for (Field<?> field : table.fields()) {
            if (!NON_SORTABLE_COLUMNS.contains(field.getName().toLowerCase())) {
                columns.put(field.getName().toLowerCase(), field);
            }
        }
        return columns;
    }

    protected <R extends Record> Select<R> limit(SelectConditionStep<R> select, Pageable pageable) {
        if (pageable == null || pageable.getSize() == -1) {
            return select;
        }

        return select
            .limit(pageable.getSize())
            .offset(pageable.getOffset() - pageable.getSize());
    }

    protected <R extends Record> Select<R> pageable(SelectConditionStep<R> select, Pageable pageable) {
        select = this.sort(select, pageable);

        return this.limit(select, pageable);
    }

    public Field<Integer> weekFromTimestamp(Field<Timestamp> timestampField) {
        return DSL.week(timestampField);
    }
}
