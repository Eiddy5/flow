package org.flow.jdbc;

import org.flow.gen.flow.Tables;
import org.jooq.Table;

import org.flow.core.lock.Lock;
import org.flow.core.models.Setting;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.ExecutionOutput;
import org.flow.core.models.executions.LogEntry;
import org.flow.core.models.executions.MetricEntry;
import org.flow.core.models.executions.TaskOutput;
import org.flow.core.models.executions.statistics.ExecutionStatistic;
import org.flow.core.models.flows.FlowInterface;
import org.flow.core.models.flows.sla.SLAMonitor;
import org.flow.core.models.kv.PersistedKvMetadata;
import org.flow.core.models.namespaces.files.NamespaceFileMetadata;
import org.flow.core.models.topologies.FlowTopology;
import org.flow.core.models.triggers.multipleflows.MultipleConditionWindow;
import org.flow.core.runners.*;
import org.flow.core.scheduler.model.TriggerState;
import org.flow.core.server.ServiceInstance;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Named;

@Factory
@Requires(missingProperty = "flow.jdbc.tables")
public class JdbcTableConfigsFactory {

    @Bean
    @Named("queues")
    public InstantiableJdbcTableConfig queues() {
        return new InstantiableJdbcTableConfig("queues", JdbcQueueItem.class, Tables.QUEUES);
    }

    @Bean
    @Named("flows")
    public InstantiableJdbcTableConfig flows() {
        return new InstantiableJdbcTableConfig("flows", FlowInterface.class, Tables.FLOWS);
    }

    @Bean
    @Named("executions")
    public InstantiableJdbcTableConfig executions() {
        return new InstantiableJdbcTableConfig("executions", Execution.class, Tables.EXECUTIONS);
    }

    @Bean
    @Named("triggers")
    public InstantiableJdbcTableConfig triggers() {
        return new InstantiableJdbcTableConfig("triggers", TriggerState.class, Tables.TRIGGERS);
    }

    @Bean
    @Named("logs")
    public InstantiableJdbcTableConfig logs() {
        return new InstantiableJdbcTableConfig("logs", LogEntry.class, Tables.LOGS);
    }

    @Bean
    @Named("metrics")
    public InstantiableJdbcTableConfig metrics() {
        return new InstantiableJdbcTableConfig("metrics", MetricEntry.class, Tables.METRICS);
    }

    @Bean
    @Named("multipleconditions")
    public InstantiableJdbcTableConfig multipleConditions() {
        return new InstantiableJdbcTableConfig("multipleconditions", MultipleConditionWindow.class, Tables.MULTIPLECONDITIONS);
    }

    @Bean
    @Named("executionstatistics")
    public InstantiableJdbcTableConfig executionStatistics() {
        return new InstantiableJdbcTableConfig("executionstatistics", ExecutionStatistic.class, Tables.EXECUTION_STATISTICS);
    }

    @Bean
    @Named("executordelayed")
    public InstantiableJdbcTableConfig executorDelayed() {
        return new InstantiableJdbcTableConfig("executordelayed", ExecutionDelay.class, Tables.EXECUTORDELAYED);
    }

    @Bean
    @Named("settings")
    public InstantiableJdbcTableConfig settings() {
        return new InstantiableJdbcTableConfig("settings", Setting.class, Tables.SETTINGS);
    }

    @Bean
    @Named("flowtopologies")
    public InstantiableJdbcTableConfig flowTopologies() {
        return new InstantiableJdbcTableConfig("flowtopologies", FlowTopology.class, Tables.FLOW_TOPOLOGIES);
    }

    @Bean
    @Named("serviceinstance")
    public InstantiableJdbcTableConfig serviceInstance() {
        return new InstantiableJdbcTableConfig("serviceinstance", ServiceInstance.class, Tables.SERVICE_INSTANCE);
    }

    @Bean
    @Named("workerjobrunning")
    public InstantiableJdbcTableConfig workerJobRunning() {
        return new InstantiableJdbcTableConfig("workerjobrunning", WorkerJobRunning.class, Tables.WORKER_JOB_RUNNING);
    }

    @Bean
    @Named("executionqueued")
    public InstantiableJdbcTableConfig executionQueued() {
        return new InstantiableJdbcTableConfig("executionqueued", ExecutionQueued.class, Tables.EXECUTION_QUEUED);
    }

    @Bean
    @Named("slamonitor")
    public InstantiableJdbcTableConfig slaMonitor() {
        return new InstantiableJdbcTableConfig("slamonitor", SLAMonitor.class, Tables.SLA_MONITOR);
    }

    @Bean
    @Named("concurrencylimit")
    public InstantiableJdbcTableConfig concurrencyLimit() {
        return new InstantiableJdbcTableConfig("concurrencylimit", ConcurrencyLimit.class, Tables.CONCURRENCY_LIMIT);
    }

    @Bean
    @Named("kvmetadata")
    public InstantiableJdbcTableConfig kvMetadata() {
        return new InstantiableJdbcTableConfig("kvmetadata", PersistedKvMetadata.class, Tables.KV_METADATA);
    }

    @Bean
    @Named("namespacefilemetadata")
    public InstantiableJdbcTableConfig namespaceFileMetadata() {
        return new InstantiableJdbcTableConfig("namespacefilemetadata", NamespaceFileMetadata.class, Tables.NAMESPACE_FILE_METADATA);
    }

    @Bean
    @Named("locks")
    public InstantiableJdbcTableConfig locks() {
        return new InstantiableJdbcTableConfig("locks", Lock.class, Tables.LOCKS);
    }

    @Bean
    @Named("taskoutputs")
    public InstantiableJdbcTableConfig outputs() {
        return new InstantiableJdbcTableConfig("taskoutputs", TaskOutput.class, Tables.TASK_OUTPUTS);
    }

    @Bean
    @Named("executionoutputs")
    public InstantiableJdbcTableConfig executionOutputs() {
        return new InstantiableJdbcTableConfig("executionoutputs", ExecutionOutput.class, Tables.EXECUTION_OUTPUTS);
    }

    /** Resolves only registered native table shapes; external table configurations remain supported. */
    static Table<?> generatedTable(String name) {
        return switch (name) {
            case "queues" -> Tables.QUEUES;
            case "flows" -> Tables.FLOWS;
            case "executions" -> Tables.EXECUTIONS;
            case "triggers" -> Tables.TRIGGERS;
            case "logs" -> Tables.LOGS;
            case "metrics" -> Tables.METRICS;
            case "multipleconditions" -> Tables.MULTIPLECONDITIONS;
            case "executionstatistics" -> Tables.EXECUTION_STATISTICS;
            case "executordelayed" -> Tables.EXECUTORDELAYED;
            case "settings" -> Tables.SETTINGS;
            case "flowtopologies" -> Tables.FLOW_TOPOLOGIES;
            case "serviceinstance" -> Tables.SERVICE_INSTANCE;
            case "workerjobrunning" -> Tables.WORKER_JOB_RUNNING;
            case "executionqueued" -> Tables.EXECUTION_QUEUED;
            case "slamonitor" -> Tables.SLA_MONITOR;
            case "concurrencylimit" -> Tables.CONCURRENCY_LIMIT;
            case "kvmetadata" -> Tables.KV_METADATA;
            case "namespacefilemetadata" -> Tables.NAMESPACE_FILE_METADATA;
            case "locks" -> Tables.LOCKS;
            case "taskoutputs" -> Tables.TASK_OUTPUTS;
            case "executionoutputs" -> Tables.EXECUTION_OUTPUTS;
            default -> null;
        };
    }

    public static class InstantiableJdbcTableConfig extends JdbcTableConfig {
        public InstantiableJdbcTableConfig(String name, @Nullable Class<?> cls, Table<?> generatedTable) {
            super(name, cls, generatedTable);
        }

        public InstantiableJdbcTableConfig(String name, @Nullable Class<?> cls, String table) {
            super(name, cls, table);
        }
    }
}
