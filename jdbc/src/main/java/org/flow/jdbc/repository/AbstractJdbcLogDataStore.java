package org.flow.jdbc.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.BiFunction;

import org.jooq.*;
import org.jooq.impl.DSL;
import org.slf4j.event.Level;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.flow.core.models.QueryFilter;
import org.flow.core.models.QueryFilter.Resource;
import org.flow.core.models.annotations.PluginProperty;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.ExecutionKind;
import org.flow.core.models.executions.LogEntry;
import org.flow.core.repositories.ArrayListTotal;
import org.flow.core.repositories.LogDataStoreInterface;
import org.flow.core.repositories.log.LogDataStoreAccessControl;
import org.flow.jdbc.JdbcTableConfig;
import org.flow.jdbc.JdbcTableConfigsFactory;
import org.flow.jdbc.JooqDSLContextWrapper;
import org.flow.jdbc.LogJdbcDataSourceProvider;

import io.micronaut.context.ApplicationContext;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import lombok.Getter;
import lombok.Setter;
import reactor.core.publisher.Flux;

public abstract class AbstractJdbcLogDataStore extends AbstractJdbcCrudRepository<LogEntry> implements LogDataStoreInterface {

    private static final Condition NORMAL_KIND_CONDITION = field("execution_kind").isNull().or(field("execution_kind").eq(ExecutionKind.NORMAL.name()));
    private static final String DATE_COLUMN = "timestamp";

    @Schema(title = "JDBC URL of a dedicated logs database; leave unset to keep logs in the main backend.")
    @PluginProperty
    @Getter
    @Setter
    private String url;

    @Schema(title = "Username for the dedicated logs database.")
    @PluginProperty
    @Getter
    @Setter
    private String username;

    @Schema(title = "Password for the dedicated logs database.")
    @PluginProperty(hidden = true)
    @Getter
    @Setter
    private String password;

    // Named 'tableName' (mapped to the 'table' key) so its getter doesn't clash with the inherited jOOQ getTable().
    @Schema(title = "Name of the log table in the dedicated logs database (defaults to 'logs').")
    @PluginProperty
    @JsonProperty("table")
    @Getter
    @Setter
    private String tableName;

    public AbstractJdbcLogDataStore(org.flow.jdbc.AbstractJdbcRepository<LogEntry> jdbcRepository) {
        super(jdbcRepository);
    }

    /**
     * No-arg constructor for log-store plugins that are deserialized from configuration and wire
     * their {@code jdbcRepository} later, in {@code init(ApplicationContext)}.
     *
     * @see org.flow.core.plugins.ApplicationContextInitializable
     */
    protected AbstractJdbcLogDataStore() {
        super(null);
    }

    /**
     * Wires this deserialized log-store plugin's runtime dependencies from the application context:
     * the {@code jdbcRepository} — either a fresh dialect
     * repository bound to the dedicated log datasource (when {@code flow.logs.<type>.url} is set),
     * or the shared {@code @Named("logs")} repository (the primary datasource) otherwise.
     *
     * @param applicationContext the application context to resolve beans from.
     * @param dedicatedRepositoryFactory builds the dialect-specific repository for the dedicated
     *        datasource (e.g. {@code (config, wrapper) -> new H2Repository<>(config, wrapper)}).
     */
    @SuppressWarnings("unchecked")
    protected void initFrom(final ApplicationContext applicationContext,
        final BiFunction<JdbcTableConfig, JooqDSLContextWrapper, org.flow.jdbc.AbstractJdbcRepository<LogEntry>> dedicatedRepositoryFactory) {
        this.accessControl = applicationContext.getBean(LogDataStoreAccessControl.class);
        // Field-injected on bean-managed repositories; set explicitly here since the plugin is deserialized.
        this.systemFlowsConfiguration = applicationContext.getBean(org.flow.core.contexts.configuration.SystemFlowsConfiguration.class);

        LogJdbcDataSourceProvider provider = applicationContext.getBean(LogJdbcDataSourceProvider.class);
        if (provider.isDedicated()) {
            JdbcTableConfig tableConfig = new JdbcTableConfigsFactory.InstantiableJdbcTableConfig("logs", LogEntry.class, provider.table());
            this.jdbcRepository = dedicatedRepositoryFactory.apply(tableConfig, provider.dedicatedWrapper());
        } else {
            this.jdbcRepository = (org.flow.jdbc.AbstractJdbcRepository<LogEntry>) applicationContext
                .findBean(org.flow.jdbc.AbstractJdbcRepository.class, Qualifiers.byName("logs"))
                .orElseThrow(() -> new IllegalStateException("No AbstractJdbcRepository named 'logs' found"));
        }
    }

    abstract protected Condition findCondition(String query);

    protected Condition findQueryCondition(String query) {
        return findCondition(query);
    }

    /**
     * Access-control + delete-audit collaborator. Defaults to the global no-op so legacy beans that
     * do not call {@link #initFrom} behave exactly as before; log-store plugins set the real bean in
     * {@code init(ApplicationContext)} (the EE bean enforces namespace ACL + publishes audit events).
     */
    protected LogDataStoreAccessControl accessControl = LogDataStoreAccessControl.GLOBAL;

    @Override
    public Page<LogEntry> find(Pageable pageable, @Nullable String tenantId, @Nullable List<QueryFilter> filters) {
        // Default to NORMAL kind only; an explicit KIND filter overrides that and selects the requested kind(s).
        // The only exception is when a filter specifies an executionId; in this case we should not restrict to any kind so the logs of that execution are always returned.
        var condition = this.filter(filters, DATE_COLUMN, Resource.LOG);
        if (!QueryFilter.hasField(filters, QueryFilter.Field.KIND) && !QueryFilter.hasField(filters, QueryFilter.Field.EXECUTION_ID)) {
            condition = NORMAL_KIND_CONDITION.and(condition);
        }
        return toOffsetPage(findPage(pageable, tenantId, condition), pageable);
    }

    /**
     * JDBC log stores paginate by offset with an exact total (the default {@code PaginationType.OFFSET}).
     * Wrap the {@link ArrayListTotal} produced by the shared paging helpers into a Micronaut {@link Page}
     * carrying that total, so the interface can also expose cursor pages from external stores.
     */
    private static Page<LogEntry> toOffsetPage(ArrayListTotal<LogEntry> result, Pageable pageable) {
        return Page.of(result, pageable, result.getTotal());
    }

    /**
     * Two log table columns don't line up with the default {@link QueryFilter.Field#name()}
     * lower-cased derivation:
     * <ul>
     * <li>{@code TASK_RUN_ID} maps to {@code taskrun_id} (one word), not {@code task_run_id}</li>
     * <li>{@code KIND} maps to {@code execution_kind}, not {@code kind}</li>
     * </ul>
     * Override the mapping for those; the rest of the column names match.
     */
    @Override
    protected Name getColumnName(QueryFilter.Field field) {
        if (field == QueryFilter.Field.TASK_RUN_ID) {
            return DSL.quotedName("taskrun_id");
        }
        if (field == QueryFilter.Field.KIND) {
            return DSL.quotedName("execution_kind");
        }
        return super.getColumnName(field);
    }

    @Override
    public Flux<LogEntry> findAsync(@Nullable String tenantId, List<QueryFilter> filters) {
        // Default to NORMAL kind only; an explicit KIND filter overrides that and selects the requested kind(s).
        // The only exception is when a filter specifies an executionId; in this case we should not restrict to any kind so the logs of that execution are always returned.
        var condition = this.filter(filters, DATE_COLUMN, Resource.LOG);
        if (!QueryFilter.hasField(filters, QueryFilter.Field.KIND) && !QueryFilter.hasField(filters, QueryFilter.Field.EXECUTION_ID)) {
            condition = NORMAL_KIND_CONDITION.and(condition);
        }
        return findAsync(tenantId, condition, field(DATE_COLUMN).asc());
    }

    @Override
    public List<KeyedLog> findAfterWithoutAcl(
        @Nullable String tenantId,
        List<QueryFilter> filters,
        Instant afterTimestamp,
        @Nullable String afterKey,
        int pageSize) {
        // Same default-kind rule as findAsync: NORMAL only unless a KIND or executionId filter is present.
        var condition = this.filter(filters, DATE_COLUMN, Resource.LOG);
        if (!QueryFilter.hasField(filters, QueryFilter.Field.KIND) && !QueryFilter.hasField(filters, QueryFilter.Field.EXECUTION_ID)) {
            condition = NORMAL_KIND_CONDITION.and(condition);
        }

        // With a key, seek strictly after the (timestamp, key) row; without one (first run or a legacy date-only
        // offset), fall back to a strict timestamp lower bound (which matches the previous START_DATE behaviour).
        Field<OffsetDateTime> dateField = field(DATE_COLUMN, OffsetDateTime.class);
        OffsetDateTime after = afterTimestamp.atOffset(ZoneOffset.UTC);
        Condition seek = afterKey == null
            ? dateField.gt(after)
            : DSL.row(dateField, KEY_FIELD).gt(DSL.row(DSL.val(after), DSL.val(afterKey)));

        final Condition finalCondition = condition;
        return this.jdbcRepository.getDslContextWrapper().transactionResult(configuration ->
            DSL.using(configuration)
                .select(KEY_FIELD, VALUE_FIELD)
                .from(this.jdbcRepository.getTable())
                .where(this.defaultFilter(tenantId))
                .and(finalCondition)
                .and(seek)
                .orderBy(dateField.asc(), KEY_FIELD.asc())
                .limit(pageSize)
                .fetch()
                .map(record -> new KeyedLog(record.get(KEY_FIELD), this.jdbcRepository.map(record)))
        );
    }

    @Override
    public List<LogEntry> findByExecutionId(String tenantId, String executionId, Level minLevel) {
        return findByExecutionId(tenantId, executionId, minLevel, true);
    }

    @Override
    public List<LogEntry> findByExecutionIdWithoutAcl(String tenantId, String executionId, Level minLevel) {
        return findByExecutionId(tenantId, executionId, minLevel, false);
    }

    private List<LogEntry> findByExecutionId(String tenantId, String executionId, Level minLevel, boolean withAccessControl) {
        return this.query(tenantId, field("execution_id").eq(executionId), minLevel, withAccessControl);
    }

    @Override
    public Page<LogEntry> findByExecutionId(String tenantId, String executionId, Level minLevel, Pageable pageable) {
        return toOffsetPage(this.query(tenantId, field("execution_id").eq(executionId), minLevel, pageable), pageable);
    }

    @Override
    public List<LogEntry> findByExecutionId(String tenantId, String namespace, String flowId, String executionId, Level minLevel) {
        return this.query(tenantId, field("execution_id").eq(executionId).and(field("namespace").eq(namespace)).and(field("flow_id").eq(flowId)), minLevel, true);
    }

    @Override
    public List<LogEntry> findByExecutionIdAndTaskId(String tenantId, String executionId, String taskId, Level minLevel) {
        return findByExecutionIdAndTaskId(tenantId, executionId, taskId, minLevel, true);
    }

    @Override
    public List<LogEntry> findByExecutionIdAndTaskIdWithoutAcl(String tenantId, String executionId, String taskId, Level minLevel) {
        return findByExecutionIdAndTaskId(tenantId, executionId, taskId, minLevel, false);
    }

    private List<LogEntry> findByExecutionIdAndTaskId(String tenantId, String executionId, String taskId, Level minLevel, boolean withAccessControl) {
        return this.query(tenantId, field("execution_id").eq(executionId).and(field("task_id").eq(taskId)), minLevel, withAccessControl);
    }

    @Override
    public Page<LogEntry> findByExecutionIdAndTaskId(String tenantId, String executionId, String taskId, Level minLevel, Pageable pageable) {
        return toOffsetPage(this.query(tenantId, field("execution_id").eq(executionId).and(field("task_id").eq(taskId)), minLevel, pageable), pageable);
    }

    @Override
    public List<LogEntry> findByExecutionIdAndTaskId(String tenantId, String namespace, String flowId, String executionId, String taskId, Level minLevel) {
        return this
            .query(tenantId, field("execution_id").eq(executionId).and(field("namespace").eq(namespace)).and(field("flow_id").eq(flowId)).and(field("task_id").eq(taskId)), minLevel, true);
    }

    @Override
    public List<LogEntry> findByExecutionIdAndTaskRunId(String tenantId, String executionId, String taskRunId, Level minLevel) {
        return findByExecutionIdAndTaskRunId(tenantId, executionId, taskRunId, minLevel, true);
    }

    @Override
    public List<LogEntry> findByExecutionIdAndTaskRunIdWithoutAcl(String tenantId, String executionId, String taskRunId, Level minLevel) {
        return findByExecutionIdAndTaskRunId(tenantId, executionId, taskRunId, minLevel, false);
    }

    private List<LogEntry> findByExecutionIdAndTaskRunId(String tenantId, String executionId, String taskRunId, Level minLevel, boolean withAccessControl) {
        return this.query(tenantId, field("execution_id").eq(executionId).and(field("taskrun_id").eq(taskRunId)), minLevel, withAccessControl);
    }

    @Override
    public Page<LogEntry> findByExecutionIdAndTaskRunId(String tenantId, String executionId, String taskRunId, Level minLevel, Pageable pageable) {
        return toOffsetPage(this.query(tenantId, field("execution_id").eq(executionId).and(field("taskrun_id").eq(taskRunId)), minLevel, pageable), pageable);
    }

    @Override
    public List<LogEntry> findByExecutionIdAndTaskRunIdAndAttempt(String tenantId, String executionId, String taskRunId, Level minLevel, Integer attempt) {
        return findByExecutionIdAndTaskRunIdAndAttempt(tenantId, executionId, taskRunId, minLevel, attempt, true);
    }

    @Override
    public List<LogEntry> findByExecutionIdAndTaskRunIdAndAttemptWithoutAcl(String tenantId, String executionId, String taskRunId, Level minLevel, Integer attempt) {
        return findByExecutionIdAndTaskRunIdAndAttempt(tenantId, executionId, taskRunId, minLevel, attempt, false);
    }

    private List<LogEntry> findByExecutionIdAndTaskRunIdAndAttempt(String tenantId, String executionId, String taskRunId, Level minLevel, Integer attempt, boolean withAccessControl) {
        return this.query(tenantId, field("execution_id").eq(executionId).and(field("taskrun_id").eq(taskRunId)).and(field("attempt_number").eq(attempt)), minLevel, withAccessControl);
    }

    @Override
    public Page<LogEntry> findByExecutionIdAndTaskRunIdAndAttempt(String tenantId, String executionId, String taskRunId, Level minLevel, Integer attempt, Pageable pageable) {
        return toOffsetPage(
            this.query(tenantId, field("execution_id").eq(executionId).and(field("taskrun_id").eq(taskRunId)).and(field("attempt_number").eq(attempt)), minLevel, pageable), pageable
        );
    }

    @Override
    public Integer purge(Execution execution) {
        // A store that can't delete on demand (canPurge() == false, e.g. a retention/TTL-only cloud store) no-ops
        // and reports 0 — callers already tolerate that. Enforced here so the contract can't be violated by a
        // reduced-capability subclass, and so no delete-audit is fired for a deletion that never happened.
        if (!canPurge()) {
            return 0;
        }
        return purge(DSL.noCondition(), field("execution_id", String.class).eq(execution.getId()));
    }

    @Override
    public Integer purge(List<Execution> executions) {
        if (!canPurge()) {
            return 0;
        }
        return purge(DSL.noCondition(), field("execution_id", String.class).in(executions.stream().map(Execution::getId).toList()));
    }

    @Override
    public void deleteByQuery(String tenantId, String executionId, String taskId, String taskRunId, Level minLevel, Integer attempt) {
        if (!canPurge()) {
            return;
        }
        accessControl.onDeleteByQuery(tenantId, executionId, taskId, taskRunId, minLevel, attempt);

        this.jdbcRepository.getDslContextWrapper().transaction(configuration ->
        {
            DSLContext context = DSL.using(configuration);

            var delete = context.delete(this.jdbcRepository.getTable()).where(this.defaultFilter(tenantId)).and(field("execution_id").eq(executionId));

            if (taskId != null) {
                delete = delete.and(field("task_id").eq(taskId));
            }

            if (taskRunId != null) {
                delete = delete.and(field("taskrun_id").eq(taskRunId));
            }

            if (minLevel != null) {
                delete = delete.and(minLevel(minLevel));
            }

            if (attempt != null) {
                delete = delete.and(field("attempt_number").eq(attempt));
            }

            delete.execute();
        });
    }

    @Override
    public void deleteByQuery(String tenantId, String namespace, String flowId, String triggerId) {
        if (!canPurge()) {
            return;
        }
        this.jdbcRepository.getDslContextWrapper().transaction(configuration ->
        {
            DSLContext context = DSL.using(configuration);

            var delete = context.delete(this.jdbcRepository.getTable()).where(this.defaultFilter(tenantId)).and(field("namespace").eq(namespace)).and(field("flow_id").eq(flowId));

            if (triggerId != null) {
                delete = delete.and(field("trigger_id").eq(triggerId));
            }

            delete.execute();
        });
    }

    @Override
    public int deleteByQuery(String tenantId, String namespace, String flowId, String executionId, List<Level> logLevels, ZonedDateTime startDate, ZonedDateTime endDate,
        boolean purgeExecutionLogs, boolean purgeNonExecutionLogs) {
        if (!canPurge()) {
            return 0;
        }
        Condition condition = buildDeleteCondition(tenantId, namespace, flowId, executionId, logLevels, startDate, endDate, purgeExecutionLogs, purgeNonExecutionLogs);

        return this.jdbcRepository.getDslContextWrapper().transactionResult(configuration ->
        {
            return DSL.using(configuration).delete(this.jdbcRepository.getTable()).where(condition).execute();
        });
    }

    private Condition buildDeleteCondition(String tenantId, String namespace, String flowId, String executionId, List<Level> logLevels, ZonedDateTime startDate, ZonedDateTime endDate,
        boolean purgeExecutionLogs, boolean purgeNonExecutionLogs) {
        Condition condition = this.defaultFilter(tenantId)
            .and(field(DATE_COLUMN).lessOrEqual(endDate.toOffsetDateTime()));

        if (startDate != null) {
            condition = condition.and(field(DATE_COLUMN).greaterOrEqual(startDate.toOffsetDateTime()));
        }
        if (namespace != null) {
            // Without a flowId, the namespace is a prefix: it matches the namespace itself and all
            // its descendants, consistent with the documented behavior and PurgeExecutions. With a
            // flowId, it targets that flow's exact namespace.
            if (flowId != null) {
                condition = condition.and(field("namespace").eq(namespace));
            } else {
                condition = condition.and(field("namespace").eq(namespace).or(field("namespace").startsWith(namespace + ".")));
            }
        }
        if (flowId != null) {
            condition = condition.and(field("flow_id").eq(flowId));
        }
        if (executionId != null) {
            condition = condition.and(field("execution_id").eq(executionId));
        }
        if (logLevels != null) {
            condition = condition.and(levelsCondition(logLevels));
        }
        if (purgeExecutionLogs && !purgeNonExecutionLogs) {
            condition = condition.and(field("execution_id").isNotNull());
        } else if (purgeNonExecutionLogs && !purgeExecutionLogs) {
            condition = condition.and(field("execution_id").isNull());
        }

        return condition;
    }

    @Override
    public void deleteByFilters(String tenantId, List<QueryFilter> filters) {
        if (!canPurge()) {
            return;
        }
        this.jdbcRepository.getDslContextWrapper().transactionResult(configuration ->
        {
            DSLContext context = DSL.using(configuration);

            var delete = context.delete(this.jdbcRepository.getTable()).where(this.defaultFilter(tenantId));
            delete = delete.and(this.filter(filters, DATE_COLUMN, Resource.LOG));

            return delete.execute();
        });
    }

    private ArrayListTotal<LogEntry> query(String tenantId, Condition condition, Level minLevel, Pageable pageable) {
        var theCondition = minLevel != null ? condition.and(minLevel(minLevel)) : condition;
        return findPage(pageable, tenantId, theCondition);
    }

    private List<LogEntry> query(String tenantId, Condition condition, Level minLevel, boolean withAccessControl) {
        var defaultFilter = withAccessControl ? this.defaultFilter(tenantId) : this.defaultFilterWithNoACL(tenantId);
        var theCondition = minLevel != null ? condition.and(minLevel(minLevel)) : condition;
        return find(defaultFilter, theCondition, field(DATE_COLUMN).sort(SortOrder.ASC));
    }

    private Condition minLevel(Level minLevel) {
        return levelsCondition(LogEntry.findLevelsByMin(minLevel));
    }

    protected Condition levelsCondition(List<Level> levels) {
        return field("level").in(levels.stream().map(level -> level.name()).toList());
    }

    protected Condition notLevelsCondition(List<Level> levels) {
        return field("level").notIn(levels.stream().map(level -> level.name()).toList());
    }

    @Override
    protected Condition defaultFilterWithNoACL(String tenantId) {
        return buildTenantCondition(tenantId);
    }

    @Override
    protected Condition defaultFilter(String tenantId) {
        return defaultFilterWithNoACL(tenantId).and(aclCondition());
    }

    /**
     * Translates the {@link org.flow.core.models.AccessScope} from {@link #accessControl} into a jOOQ
     * condition: {@code GLOBAL} → no restriction, {@code DENY_ALL} → match nothing,
     * {@code NAMESPACES} → the namespace (or its children) is in the allowed set.
     */
    protected Condition aclCondition() {
        org.flow.core.models.AccessScope scope = accessControl.namespaceScope();
        return switch (scope.kind()) {
            case GLOBAL -> DSL.trueCondition();
            case DENY_ALL -> DSL.falseCondition();
            case NAMESPACES -> {
                List<Condition> ors = new ArrayList<>();
                for (String namespace : scope.namespaces()) {
                    ors.add(field("namespace").eq(namespace));
                    ors.add(field("namespace").startsWith(namespace + "."));
                }
                yield DSL.or(ors);
            }
        };
    }

    @Override
    protected Condition defaultFilter(Boolean allowDeleted) {
        return this.defaultFilter();
    }

    @Override
    protected Condition defaultFilterWithNoACL(String tenantId, boolean deleted) {
        return this.defaultFilterWithNoACL(tenantId);
    }

    @Override
    protected Condition defaultFilter(String tenantId, boolean allowDeleted) {
        return this.defaultFilter(tenantId);
    }

    @Override
    protected Condition defaultFilter() {
        return DSL.trueCondition();
    }

}
