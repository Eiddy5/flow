package org.flow.jdbc;

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
        return new InstantiableJdbcTableConfig("queues", JdbcQueueItem.class, "queues");
    }

    @Bean
    @Named("flows")
    public InstantiableJdbcTableConfig flows() {
        return new InstantiableJdbcTableConfig("flows", FlowInterface.class, "flows");
    }

    @Bean
    @Named("executions")
    public InstantiableJdbcTableConfig executions() {
        return new InstantiableJdbcTableConfig("executions", Execution.class, "executions");
    }

    @Bean
    @Named("triggers")
    public InstantiableJdbcTableConfig triggers() {
        return new InstantiableJdbcTableConfig("triggers", TriggerState.class, "triggers");
    }

    @Bean
    @Named("logs")
    public InstantiableJdbcTableConfig logs() {
        return new InstantiableJdbcTableConfig("logs", LogEntry.class, "logs");
    }

    @Bean
    @Named("metrics")
    public InstantiableJdbcTableConfig metrics() {
        return new InstantiableJdbcTableConfig("metrics", MetricEntry.class, "metrics");
    }

    @Bean
    @Named("multipleconditions")
    public InstantiableJdbcTableConfig multipleConditions() {
        return new InstantiableJdbcTableConfig("multipleconditions", MultipleConditionWindow.class, "multipleconditions");
    }

    @Bean
    @Named("executionstatistics")
    public InstantiableJdbcTableConfig executionStatistics() {
        return new InstantiableJdbcTableConfig("executionstatistics", ExecutionStatistic.class, "execution_statistics");
    }

    @Bean
    @Named("executordelayed")
    public InstantiableJdbcTableConfig executorDelayed() {
        return new InstantiableJdbcTableConfig("executordelayed", ExecutionDelay.class, "executordelayed");
    }

    @Bean
    @Named("settings")
    public InstantiableJdbcTableConfig settings() {
        return new InstantiableJdbcTableConfig("settings", Setting.class, "settings");
    }

    @Bean
    @Named("flowtopologies")
    public InstantiableJdbcTableConfig flowTopologies() {
        return new InstantiableJdbcTableConfig("flowtopologies", FlowTopology.class, "flow_topologies");
    }

    @Bean
    @Named("serviceinstance")
    public InstantiableJdbcTableConfig serviceInstance() {
        return new InstantiableJdbcTableConfig("serviceinstance", ServiceInstance.class, "service_instance");
    }

    @Bean
    @Named("workerjobrunning")
    public InstantiableJdbcTableConfig workerJobRunning() {
        return new InstantiableJdbcTableConfig("workerjobrunning", WorkerJobRunning.class, "worker_job_running");
    }

    @Bean
    @Named("executionqueued")
    public InstantiableJdbcTableConfig executionQueued() {
        return new InstantiableJdbcTableConfig("executionqueued", ExecutionQueued.class, "execution_queued");
    }

    @Bean
    @Named("slamonitor")
    public InstantiableJdbcTableConfig slaMonitor() {
        return new InstantiableJdbcTableConfig("slamonitor", SLAMonitor.class, "sla_monitor");
    }

    @Bean
    @Named("concurrencylimit")
    public InstantiableJdbcTableConfig concurrencyLimit() {
        return new InstantiableJdbcTableConfig("concurrencylimit", ConcurrencyLimit.class, "concurrency_limit");
    }

    @Bean
    @Named("kvmetadata")
    public InstantiableJdbcTableConfig kvMetadata() {
        return new InstantiableJdbcTableConfig("kvmetadata", PersistedKvMetadata.class, "kv_metadata");
    }

    @Bean
    @Named("namespacefilemetadata")
    public InstantiableJdbcTableConfig namespaceFileMetadata() {
        return new InstantiableJdbcTableConfig("namespacefilemetadata", NamespaceFileMetadata.class, "namespace_file_metadata");
    }

    @Bean
    @Named("locks")
    public InstantiableJdbcTableConfig locks() {
        return new InstantiableJdbcTableConfig("locks", Lock.class, "locks");
    }

    @Bean
    @Named("taskoutputs")
    public InstantiableJdbcTableConfig outputs() {
        return new InstantiableJdbcTableConfig("taskoutputs", TaskOutput.class, "task_outputs");
    }

    @Bean
    @Named("executionoutputs")
    public InstantiableJdbcTableConfig executionOutputs() {
        return new InstantiableJdbcTableConfig("executionoutputs", ExecutionOutput.class, "execution_outputs");
    }

    public static class InstantiableJdbcTableConfig extends JdbcTableConfig {
        public InstantiableJdbcTableConfig(String name, @Nullable Class<?> cls, String table) {
            super(name, cls, table);
        }
    }
}
