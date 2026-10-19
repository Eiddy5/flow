package org.flow.core.jdbc.repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

import org.jooq.*;
import org.jooq.impl.DSL;
import org.slf4j.event.Level;

import org.flow.gen.flow.Tables;
import org.flow.core.contexts.configuration.SystemFlowsConfiguration;
import org.flow.core.exceptions.InvalidQueryFiltersException;
import org.flow.core.models.QueryFilter;
import org.flow.core.models.QueryFilter.Op;
import org.flow.core.models.QueryFilter.Resource;
import org.flow.core.models.executions.LogEntry;
import org.flow.core.models.flows.FlowScope;
import org.flow.core.models.flows.State;
import org.flow.core.models.AccessScope;
import org.flow.core.repositories.ExecutionRepositoryInterface.ChildFilter;
import org.flow.core.repositories.NamespaceAccessControl;
import org.flow.core.utils.DateUtils;
import org.flow.core.utils.Either;
import org.flow.core.utils.Enums;
import org.flow.core.utils.ListUtils;
import org.flow.core.utils.TypeConverter;

import io.micronaut.context.BeanProvider;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import lombok.Getter;

public abstract class AbstractJdbcRepository {
    protected abstract Table<?> table();

    protected Field<String> keyField() {
        return Objects.requireNonNull(table().field(Tables.FLOWS.KEY));
    }

    protected Field<JSONB> valueField() {
        return Objects.requireNonNull(table().field(Tables.FLOWS.VALUE));
    }

    protected Field<String> tenantIdField() {
        return Objects.requireNonNull(table().field(Tables.FLOWS.TENANT_ID));
    }

    protected Field<Boolean> deletedField() {
        return Objects.requireNonNull(table().field(Tables.FLOWS.DELETED));
    }

    protected static final int FETCH_SIZE = 100;

    /**
     * Operations valid on the {@link QueryFilter.Field#LABELS} field when its value is a plain
     * string, i.e. {@code filters[labels][OP]=value} with no label key. Every other operation
     * requires a keyed map value ({@code filters[labels][OP][key]=value}).
     */
    private static final Set<Op> SCALAR_LABEL_OPS = EnumSet.of(Op.CONTAINS, Op.NOT_CONTAINS, Op.IS_NULL, Op.IS_NOT_NULL);

    @Getter
    @Inject
    // Micronaut field-injects this for bean-managed repositories; log-store plugins (deserialized,
    // not bean-managed) set it in AbstractJdbcLogDataStore.initFrom — hence protected, not private.
    protected SystemFlowsConfiguration systemFlowsConfiguration;

    @Inject
    // Injected lazily as a provider: the EE bean depends on the
    // role repository, which itself extends this base, so eager injection would be a circular dependency.
    protected BeanProvider<NamespaceAccessControl> namespaceAccessControlProvider;

    protected NamespaceAccessControl namespaceAccessControl() {
        return namespaceAccessControlProvider != null ? namespaceAccessControlProvider.get() : NamespaceAccessControl.GLOBAL;
    }

    protected Condition defaultFilter() {
        return deletedField().eq(false);
    }

    protected Condition defaultFilter(Boolean allowDeleted) {
        return allowDeleted ? deletedField().in(true, false) : deletedField().eq(false);
    }

    protected Condition defaultFilter(String tenantId) {
        return this.defaultFilter(tenantId, false);
    }

    protected Condition defaultFilter(String tenantId, boolean allowDeleted) {
        var tenant = buildTenantCondition(tenantId);

        // Always include `deleted` in the query filters as most database optimizers can only use and index if the leftmost columns are used in the query
        return allowDeleted ? tenant.and(deletedField().in(true, false)) : tenant.and(deletedField().eq(false));
    }

    protected Condition defaultFilterWithNoACL(String tenantId) {
        return defaultFilterWithNoACL(tenantId, false);
    }

    protected Condition defaultFilterWithNoACL(String tenantId, boolean deleted) {
        var tenant = buildTenantCondition(tenantId);

        // Always include `deleted` in the query filters as most database optimizers can only use and index if the leftmost columns are used in the query
        return deleted ? tenant.and(deletedField().in(true, false)) : tenant.and(deletedField().eq(false));
    }

    protected Condition aclCondition(Resource resource) {
        return aclCondition(resource, table().field(Tables.FLOWS.NAMESPACE));
    }

    /**
     * The one namespace-ACL clause every JDBC repository ANDs onto its tenant filter, translating the
     * backend-agnostic {@link AccessScope} from {@link NamespaceAccessControl} into jOOQ: {@code GLOBAL}
     * imposes no restriction, {@code DENY_ALL} matches nothing, {@code NAMESPACES} matches a granted
     * namespace or its dot-delimited subtree ({@code org.flowx} is not inside {@code org.flow}).
     *
     * @param namespaceColumn the column holding the namespace, which is not always named {@code namespace}.
     */
    protected Condition aclCondition(Resource resource, Field<String> namespaceColumn) {
        AccessScope scope = namespaceAccessControl().namespaceScope(resource);
        Field<String> column = namespaceColumn;
        return switch (scope.kind()) {
            case GLOBAL -> DSL.noCondition();
            case DENY_ALL -> DSL.falseCondition();
            case NAMESPACES -> {
                List<Condition> ors = new ArrayList<>(scope.namespaces().size() * 2);
                for (String namespace : scope.namespaces()) {
                    ors.add(column.eq(namespace));
                    ors.add(column.startsWith(namespace + "."));
                }
                yield DSL.or(ors);
            }
        };
    }

    protected Condition buildTenantCondition(String tenantId) {
        return tenantId == null ? tenantIdField().isNull() : tenantIdField().eq(tenantId);
    }

    /**
     * Null-safe equality condition: {@code field = value} in SQL never matches a NULL value, so a
     * plain {@code field.eq(value)} silently drops rows when {@code value} is null. Use this whenever
     * {@code value} models an optional scope that is genuinely persisted as {@code NULL}.
     */
    // Used in EE
    protected <T> Condition eqOrIsNull(Field<T> field, T value) {
        return value == null ? field.isNull() : field.eq(value);
    }

    protected List<Field<?>> groupByFields(Duration duration) {
        return groupByFields(duration, null, null);
    }

    protected List<Field<?>> groupByFields(Duration duration, boolean withAs) {
        return groupByFields(duration, null, null, withAs);
    }

    protected Field<Integer> weekFromTimestamp(Field<Timestamp> timestampField) {
        return DSL.week(timestampField);
    }

    /**
     * The timestamp expression that {@link #groupByFields} extracts date parts from.
     * <p>
     * It must yield <em>UTC</em> wall-clock parts, because
     * {@link org.flow.core.jdbc.AbstractJdbcRepository#getDate} reassembles them in UTC. PostgreSQL
     * stores {@code TIMESTAMPTZ}; its metric repository overrides this expression to normalize
     * the session timezone to UTC before extracting date parts.
     */
    protected Field<Timestamp> groupByTimestampField(Field<?> dateField) {
        return dateField.cast(Timestamp.class);
    }

    protected List<Field<?>> groupByFields(Duration duration, @Nullable Field<?> dateField, @Nullable DateUtils.GroupType groupBy) {
        return groupByFields(duration, dateField, groupBy, true);
    }

    protected List<Field<?>> groupByFields(Duration duration, @Nullable Field<?> dateField, @Nullable DateUtils.GroupType groupBy, boolean withAs) {
        Field<?> field = dateField != null ? dateField : Objects.requireNonNull(table().field(Tables.METRICS.TIMESTAMP));
        Field<Timestamp> timestamp = groupByTimestampField(field);
        Field<Integer> month = withAs ? DSL.month(timestamp).as("month") : DSL.month(timestamp);
        Field<Integer> year = withAs ? DSL.year(timestamp).as("year") : DSL.year(timestamp);
        Field<Integer> day = withAs ? DSL.day(timestamp).as("day") : DSL.day(timestamp);
        Field<Integer> week = withAs ? weekFromTimestamp(timestamp).as("week") : weekFromTimestamp(timestamp);
        Field<Integer> hour = withAs ? DSL.hour(timestamp).as("hour") : DSL.hour(timestamp);
        Field<Integer> minute = withAs ? DSL.minute(timestamp).as("minute") : DSL.minute(timestamp);

        if (groupBy == DateUtils.GroupType.MONTH || duration.toDays() > DateUtils.GroupValue.MONTH.getValue()) {
            return List.of(year, month);
        } else if (groupBy == DateUtils.GroupType.WEEK || duration.toDays() > DateUtils.GroupValue.WEEK.getValue()) {
            return List.of(year, week);
        } else if (groupBy == DateUtils.GroupType.DAY || duration.toDays() > DateUtils.GroupValue.DAY.getValue()) {
            return List.of(year, month, day);
        } else if (groupBy == DateUtils.GroupType.HOUR || duration.toHours() > DateUtils.GroupValue.HOUR.getValue()) {
            return List.of(year, month, day, hour);
        } else {
            return List.of(year, month, day, hour, minute);
        }
    }

    protected Condition filter(
        List<QueryFilter> filters,
        Field<?> dateColumn,
        Resource resource) {
        if (filters == null || filters.isEmpty()) {
            return DSL.noCondition();
        }
        QueryFilter.validateQueryFilters(filters, resource);
        return andOf(filters, dateColumn);
    }

    private Condition andOf(List<QueryFilter> items, Field<?> dateColumn) {
        return items.stream()
            .map(f -> toCondition(f, dateColumn))
            .reduce(DSL.noCondition(), Condition::and);
    }

    private Condition orOf(List<QueryFilter> items, Field<?> dateColumn) {
        return items.stream()
            .map(f -> toCondition(f, dateColumn))
            .reduce(DSL.noCondition(), Condition::or);
    }

    private Condition toCondition(QueryFilter filter, Field<?> dateColumn) {
        if (filter.isLeaf()) {
            return getConditionOnField(filter.field(), filter.value(), filter.operation(), dateColumn);
        }
        return switch (filter.logical()) {
            case AND -> andOf(filter.children(), dateColumn);
            case OR -> orOf(filter.children(), dateColumn);
        };
    }

    /**
     *
     * @param dateColumn the JDBC column name of the logical date to filter on with {@link org.flow.core.models.QueryFilter.Field#START_DATE} and/or {@link QueryFilter.Field#END_DATE}
     */
    protected final Condition getConditionOnField(
        QueryFilter.Field field,
        Object value,
        QueryFilter.Op operation,
        @Nullable Field<?> dateColumn) {
        if (field.equals(QueryFilter.Field.QUERY)) {
            return handleQuery(value, operation);
        }
        // Handling for Field.STATE
        if (field.equals(QueryFilter.Field.STATE)) {

            return generateStateCondition(value, operation);
        }
        // Handle Field.CHILD_FILTER
        if (field.equals(QueryFilter.Field.CHILD_FILTER)) {
            return handleChildFilter(value, operation);
        }
        // Handling for Field.LEVEL
        if (field.equals(QueryFilter.Field.LEVEL)) {
            return handleLevelField(value, operation);
        }
        // Handling for Field.ATTEMPT_NUMBER — integer column, URL value arrives as String
        // and Postgres won't auto-coerce '1' to integer. Parse before binding.
        if (field.equals(QueryFilter.Field.ATTEMPT_NUMBER)) {
            return handleAttemptNumberField(value, operation);
        }

        // Special handling for START_DATE and END_DATE
        if (field == QueryFilter.Field.START_DATE || field == QueryFilter.Field.END_DATE || field == QueryFilter.Field.UPDATED) {
            if (dateColumn == null) {
                throw new InvalidQueryFiltersException("When creating filtering on START_DATE and/or END_DATE, dateColumn is required but was null");
            }
            return getDateCondition(value, operation, dateColumn);
        }

        if (field == QueryFilter.Field.CREATED) {
            return createdCondition(value, operation, dateColumn);
        }

        if (field == QueryFilter.Field.ENABLED) {
            return getEnabledCondition(value, operation);
        }

        if (field == QueryFilter.Field.INSTANCE_OWNER) {
            return getInstanceOwnerCondition(value, operation);
        }

        if (field == QueryFilter.Field.STATUS) {
            return statusCondition(value, operation);
        }
        if (field == QueryFilter.Field.GROUP) {
            return groupCondition(value, operation);
        }

        if (field == QueryFilter.Field.NAME) {
            return nameCondition(value, operation);
        }

        if (field == QueryFilter.Field.TAGS) {
            return tagsCondition(value, operation);
        }

        if (field == QueryFilter.Field.ASSIGNEE) {
            return assigneeCondition(value, operation);
        }

        if (field == QueryFilter.Field.EXPIRATION_DATE) {
            return getDateCondition(value, operation, getColumnField(QueryFilter.Field.EXPIRATION_DATE));
        }

        if (field == QueryFilter.Field.SCOPE) {
            return applyScopeCondition(value, operation);
        }

        if (field.equals(QueryFilter.Field.LABELS)) {
            if (value instanceof Map<?, ?> map) {
                return findLabelCondition(Either.left(map), operation);
            } else if (value instanceof String string) {
                if (!SCALAR_LABEL_OPS.contains(operation)) {
                    throw new InvalidQueryFiltersException(
                        "Operation %s on the labels field requires a label key, as in filters[labels][%s][<key>]=<value>. Operations supported without a key are %s.".formatted(
                            operation, operation, SCALAR_LABEL_OPS
                        )
                    );
                }
                return findLabelCondition(Either.right(string), operation);
            } else {
                throw new InvalidQueryFiltersException("Label field value must be instance of Map or String");
            }
        }

        if (field == QueryFilter.Field.TRIGGER_STATE) {
            return applyTriggerStateCondition(value, operation);
        }

        if (field.equals(QueryFilter.Field.METADATA)) {
            return findMetadataCondition((Map<?, ?>) value, operation);
        }

        if (field == QueryFilter.Field.TYPE) {
            return typeCondition(value, operation);
        }

        if (field == QueryFilter.Field.TAGS) {
            return tagsCondition(value, operation);
        }

        if (field == QueryFilter.Field.RESOURCES) {
            return resourceTypesCondition(value, operation);
        }

        if (field == QueryFilter.Field.DETAILS) {
            return detailsCondition(value, operation);
        }

        if (field == QueryFilter.Field.TAGS) {
            return tagsCondition(value, operation);
        }

        if (field == QueryFilter.Field.LOCKED) {
            return lockedCondition(value, operation);
        }

        if (field == QueryFilter.Field.LAST_TRIGGERED_DATE) {
            return lastTriggeredDateCondition(value, operation);
        }

        if (field == QueryFilter.Field.NEXT_EXECUTION_DATE) {
            return nextExecutionDateCondition(value, operation);
        }

        if (field == QueryFilter.Field.TIME_RANGE) {
            return timeRangeCondition(value, operation);
        }

        return defaultHandlers(field, value, operation);
    }

    protected Condition defaultHandlers(
        QueryFilter.Field field,
        Object value,
        QueryFilter.Op operation) {
        return defaultHandlers(getColumnField(field), value, operation);
    }

    private <T> Condition defaultHandlers(Field<T> column, Object value, QueryFilter.Op operation) {

        // Default handling for other fields
        return switch (operation) {
            case EQUALS -> {
                Object v = primitiveOrToString(value);
                yield v == null ? column.isNull() : column.eq(column.getDataType().convert(v));
            }
            case NOT_EQUALS -> {
                Object v = primitiveOrToString(value);
                yield v == null ? column.isNotNull() : column.ne(column.getDataType().convert(v));
            }
            case GREATER_THAN -> {
                if (value == null) {
                    throw new InvalidQueryFiltersException("GREATER_THAN operation requires a non-null value");
                }
                yield column.greaterThan(column.getDataType().convert(value));
            }
            case LESS_THAN -> {
                if (value == null) {
                    throw new InvalidQueryFiltersException("LESS_THAN operation requires a non-null value");
                }
                yield column.lessThan(column.getDataType().convert(value));
            }
            case IN -> {
                if (value == null) {
                    throw new InvalidQueryFiltersException("IN operation requires a non-null value");
                }
                yield column.in(ListUtils.convertToListString(value).stream().map(column.getDataType()::convert).toList());
            }
            case NOT_IN -> {
                if (value == null) {
                    throw new InvalidQueryFiltersException("NOT_IN operation requires a non-null value");
                }
                yield column.notIn(ListUtils.convertToListString(value).stream().map(column.getDataType()::convert).toList());
            }
            case STARTS_WITH -> {
                String s = requireStringValue(value, "STARTS_WITH");
                yield column.startsWith(column.getDataType().convert(s));
            }
            case ENDS_WITH -> {
                String s = requireStringValue(value, "ENDS_WITH");
                yield column.endsWith(column.getDataType().convert(s));
            }
            case CONTAINS -> {
                String s = requireStringValue(value, "CONTAINS");
                yield column.contains(column.getDataType().convert(s));
            }
            case REGEX -> {
                String s = requireStringValue(value, "REGEX");
                yield column.likeRegex(s);
            }
            case PREFIX -> {
                String s = requireStringValue(value, "PREFIX");
                yield column.eq(column.getDataType().convert(s))
                    .or(column.startsWith(column.getDataType().convert(s + ".")));
            }
            default -> throw new InvalidQueryFiltersException("Unsupported operation: " + operation);
        };
    }

    private static String requireStringValue(Object value, String operationName) {
        if (value == null) {
            throw new InvalidQueryFiltersException(operationName + " operation requires a non-null string value");
        }
        if (value instanceof List<?>) {
            throw new InvalidQueryFiltersException(operationName + " operation requires a string value, got a List");
        }
        return primitiveOrToString(value).toString();
    }

    private Condition getDateCondition(Object value, Op operation, Field<?> dateColumn) {
        OffsetDateTime dateTime = TypeConverter.toZonedDateTime(value).toOffsetDateTime();
        return applyDateCondition(dateTime, operation, dateColumn);
    }

    protected static Object primitiveOrToString(Object o) {
        if (o == null)
            return null;

        if (
            o instanceof Boolean
                || o instanceof Number
                || o instanceof Character
                || o instanceof String
        ) {
            return o;
        }

        return o.toString();
    }

    protected Field<?> getColumnField(QueryFilter.Field field) {
        Field<?> column = table().field(DSL.name(field.name().toLowerCase()));
        if (column == null) {
            throw new InvalidQueryFiltersException("Unsupported field: " + field);
        }
        return column;
    }

    protected Condition findQueryCondition(String query) {
        throw new InvalidQueryFiltersException("Unsupported operation: ");
    }

    public Condition findLabelCondition(Either<Map<?, ?>, String> value, QueryFilter.Op operation) {
        throw new InvalidQueryFiltersException("Unsupported operation: " + operation);
    }

    protected Condition findMetadataCondition(Map<?, ?> metadata, QueryFilter.Op operation) {
        throw new InvalidQueryFiltersException("Unsupported operation: " + operation);
    }

    protected Condition getEnabledCondition(Object value, Op operation) {
        return defaultHandlers(QueryFilter.Field.ENABLED, value, operation);
    }

    protected Condition getInstanceOwnerCondition(Object value, Op operation) {
        throw new InvalidQueryFiltersException("getInstanceOwnerCondition must be overridden for JSONB-backed instanceOwner field");
    }

    protected Condition tagsCondition(Object value, QueryFilter.Op operation) {
        return defaultHandlers(QueryFilter.Field.TAGS, value, operation);
    }

    protected Condition assigneeCondition(Object value, QueryFilter.Op operation) {
        return defaultHandlers(QueryFilter.Field.ASSIGNEE, value, operation);
    }

    // Generate the condition for Field.STATE
    @SuppressWarnings("unchecked")
    protected Condition generateStateCondition(Object value, QueryFilter.Op operation) {
        List<State.Type> stateList = switch (value) {
            case List<?> list when !list.isEmpty() && list.getFirst() instanceof State.Type -> (List<State.Type>) list;
            case List<?> list -> list.stream().map(item -> State.Type.valueOf(item.toString())).toList();
            case State.Type state -> List.of(state);
            case String state -> List.of(State.Type.valueOf(state));
            default ->
                throw new InvalidQueryFiltersException("Field 'state' requires a State.Type or List<State.Type> value");
        };

        return switch (operation) {
            case IN, EQUALS -> statesFilter(stateList);
            case NOT_IN, NOT_EQUALS -> DSL.not(statesFilter(stateList));
            default -> throw new InvalidQueryFiltersException("Unsupported operation for State.Type: " + operation);
        };
    }

    protected Condition lockedCondition(Object value, QueryFilter.Op operation) {
        throw new InvalidQueryFiltersException("Unsupported field: LOCKED");
    }

    protected Condition lastTriggeredDateCondition(Object value, QueryFilter.Op operation) {
        throw new InvalidQueryFiltersException("Unsupported field: LAST_TRIGGERED_DATE");
    }

    protected Condition nextExecutionDateCondition(Object value, QueryFilter.Op operation) {
        throw new InvalidQueryFiltersException("Unsupported field: NEXT_EXECUTION_DATE");
    }

    protected Condition timeRangeCondition(Object value, QueryFilter.Op operation) {
        throw new InvalidQueryFiltersException("Unsupported field: TIME_RANGE");
    }

    protected Condition createdCondition(Object value, QueryFilter.Op operation, @Nullable Field<?> dateColumn) {
        if (dateColumn == null) {
            throw new InvalidQueryFiltersException("When filtering on CREATED, dateColumn is required but was null");
        }
        return getDateCondition(value, operation, dateColumn);
    }

    protected Condition statusCondition(Object value, QueryFilter.Op operation) {
        return defaultHandlers(QueryFilter.Field.STATUS, value, operation);
    }

    protected Condition groupCondition(Object value, QueryFilter.Op operation) {
        throw new InvalidQueryFiltersException("Unsupported operation: " + operation);
    }

    protected Condition nameCondition(Object value, QueryFilter.Op operation) {
        return defaultHandlers(QueryFilter.Field.NAME, value, operation);
    }

    protected Condition typeCondition(Object value, QueryFilter.Op operation) {
        return defaultHandlers(QueryFilter.Field.TYPE, value, operation);
    }

    protected Condition resourceTypesCondition(Object value, QueryFilter.Op operation) {
        return defaultHandlers(QueryFilter.Field.RESOURCES, value, operation);
    }

    protected Condition detailsCondition(Object value, QueryFilter.Op operation) {
        return defaultHandlers(QueryFilter.Field.DETAILS, value, operation);
    }

    protected Condition statesFilter(List<State.Type> state) {
        return table().field(Tables.EXECUTIONS.STATE_CURRENT)
            .in(state.stream().map(item -> org.flow.gen.flow.enums.StateType.valueOf(item.name())).toList());
    }

    private Condition handleQuery(Object value, QueryFilter.Op operation) {
        Condition condition = findQueryCondition(value.toString());

        return switch (operation) {
            case EQUALS -> condition;
            case NOT_EQUALS -> condition.not();
            default -> throw new InvalidQueryFiltersException("Unsupported operation for QUERY field: " + operation);
        };
    }

    // Handle CHILD_FILTER field logic
    private Condition handleChildFilter(Object value, Op operation) {
        ChildFilter childFilter = (value instanceof String val) ? ChildFilter.valueOf(val) : (ChildFilter) value;

        return switch (operation) {
            case EQUALS -> childFilter.equals(ChildFilter.CHILD) ? table().field(Tables.EXECUTIONS.TRIGGER_EXECUTION_ID).isNotNull() : table().field(Tables.EXECUTIONS.TRIGGER_EXECUTION_ID).isNull();
            case NOT_EQUALS -> childFilter.equals(ChildFilter.CHILD) ? table().field(Tables.EXECUTIONS.TRIGGER_EXECUTION_ID).isNull() : table().field(Tables.EXECUTIONS.TRIGGER_EXECUTION_ID).isNotNull();
            default -> throw new InvalidQueryFiltersException("Unsupported operation for child filter field: " + operation);
        };
    }

    private Condition handleLevelField(Object value, QueryFilter.Op operation) {
        return switch (operation) {
            case GREATER_THAN_OR_EQUAL_TO -> levelsCondition(LogEntry.findLevelsByMin(toLevel(value)));
            case LESS_THAN_OR_EQUAL_TO -> levelsCondition(LogEntry.findLevelsByMax(toLevel(value)));
            case IN -> levelsCondition(toLevelList(value));
            case NOT_IN -> notLevelsCondition(toLevelList(value));
            default -> throw new InvalidQueryFiltersException(
                "Unsupported operation for LEVEL: " + operation
            );
        };
    }

    private static Level toLevel(Object value) {
        return value instanceof Level level ? level : Level.valueOf((String) value);
    }

    private static List<Level> toLevelList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(AbstractJdbcRepository::toLevel).toList();
        }
        return List.of(toLevel(value));
    }

    protected Condition levelsCondition(List<Level> levels) {
        return table().field(Tables.LOGS.LEVEL).in(levels.stream().map(level -> org.flow.gen.flow.enums.LogLevel.valueOf(level.name())).toList());
    }

    protected Condition notLevelsCondition(List<Level> levels) {
        return table().field(Tables.LOGS.LEVEL).notIn(levels.stream().map(level -> org.flow.gen.flow.enums.LogLevel.valueOf(level.name())).toList());
    }

    protected Condition handleAttemptNumberField(Object value, QueryFilter.Op operation) {
        Field<Integer> column = Objects.requireNonNull(table().field(Tables.LOGS.ATTEMPT_NUMBER));
        return switch (operation) {
            case EQUALS -> column.eq(TypeConverter.toInteger(value));
            case NOT_EQUALS -> column.ne(TypeConverter.toInteger(value));
            case IN -> column.in(toIntegerList(value));
            case NOT_IN -> column.notIn(toIntegerList(value));
            default -> throw new InvalidQueryFiltersException(
                "Unsupported operation for ATTEMPT_NUMBER: " + operation
            );
        };
    }

    private static List<Integer> toIntegerList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(TypeConverter::toInteger).toList();
        }
        return List.of(TypeConverter.toInteger(value));
    }

    /**
     * 为所属模块的时间字段构建比较条件，保留 PostgreSQL 原有时间类型比较。
     * @param dateTime 待比较时间；不能为 null
     * @param operation 过滤操作；不能为 null
     * @param dateField gen 表提供的时间字段；不能为 null
     * @return 使用该字段和时间参数构建的 SQL 条件
     * @throws InvalidQueryFiltersException 操作不支持时间比较时抛出
     */
    protected Condition applyDateCondition(OffsetDateTime dateTime, QueryFilter.Op operation, Field<?> dateField) {
        String operator = switch (operation) {
            case LESS_THAN -> "<";
            case LESS_THAN_OR_EQUAL_TO -> "<=";
            case GREATER_THAN -> ">";
            case GREATER_THAN_OR_EQUAL_TO -> ">=";
            case EQUALS -> "=";
            case NOT_EQUALS -> "<>";
            default -> throw new InvalidQueryFiltersException("Unsupported operation for date condition: " + operation);
        };
        return DSL.condition("{0} " + operator + " {1}", dateField, DSL.val(dateTime));
    }

    private Condition applyScopeCondition(Object value, QueryFilter.Op operation) {
        List<FlowScope> flowScopes = Enums.fromList(value, FlowScope.class);
        String systemNamespace = this.systemFlowsConfiguration.namespace();

        return switch (operation) {
            case EQUALS, NOT_EQUALS -> {
                if (flowScopes.size() > 1) {
                    throw new InvalidQueryFiltersException("Only one scope can be used at a time with " + operation);
                }
                FlowScope scope = flowScopes.getFirst();
                yield switch (operation) {
                    case EQUALS -> FlowScope.USER.equals(scope) ? table().field(Tables.FLOWS.NAMESPACE).ne(systemNamespace) : table().field(Tables.FLOWS.NAMESPACE).eq(systemNamespace);
                    case NOT_EQUALS -> FlowScope.USER.equals(scope) ? table().field(Tables.FLOWS.NAMESPACE).eq(systemNamespace) : table().field(Tables.FLOWS.NAMESPACE).ne(systemNamespace);
                    default -> throw new InvalidQueryFiltersException("Unreachable");
                };
            }
            case IN -> {
                boolean includesUser = flowScopes.contains(FlowScope.USER);
                boolean includesSystem = flowScopes.contains(FlowScope.SYSTEM);
                if (includesUser && includesSystem)
                    yield DSL.noCondition();
                else if (includesUser)
                    yield table().field(Tables.FLOWS.NAMESPACE).ne(systemNamespace);
                else
                    yield table().field(Tables.FLOWS.NAMESPACE).eq(systemNamespace);
            }
            case NOT_IN -> {
                boolean excludesUser = flowScopes.contains(FlowScope.USER);
                boolean excludesSystem = flowScopes.contains(FlowScope.SYSTEM);
                if (excludesUser && excludesSystem)
                    yield DSL.falseCondition();
                else if (excludesUser)
                    yield table().field(Tables.FLOWS.NAMESPACE).eq(systemNamespace);
                else
                    yield table().field(Tables.FLOWS.NAMESPACE).ne(systemNamespace);
            }
            default -> throw new InvalidQueryFiltersException("Unsupported operation for SCOPE: " + operation);
        };
    }

    private Condition applyTriggerStateCondition(Object value, QueryFilter.Op operation) {
        String triggerState = value.toString();
        Boolean isDisabled = switch (triggerState) {
            case "disabled" -> true;
            case "enabled" -> false;
            default -> null;
        };
        if (isDisabled == null) {
            return DSL.noCondition();
        }
        // A trigger is off for the user when either its runtime flag or its flow definition disables it,
        // so both have to be read here: `disabled` holds only the runtime one.
        Condition disabled = table().field(Tables.TRIGGERS.DISABLED).isTrue()
            .or(table().field(Tables.TRIGGERS.SOURCE_DISABLED).isTrue());

        boolean matchesDisabled = switch (operation) {
            case EQUALS -> isDisabled;
            case NOT_EQUALS -> !isDisabled;
            default -> throw new InvalidQueryFiltersException("Unsupported operation for Trigger State: " + operation);
        };
        return matchesDisabled ? disabled : DSL.not(disabled);
    }

}
