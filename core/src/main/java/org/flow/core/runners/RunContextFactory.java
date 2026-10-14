package org.flow.core.runners;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import com.google.common.annotations.VisibleForTesting;

import org.flow.core.assets.AssetManagerFactory;
import org.flow.core.contexts.configuration.FlowConfiguration;
import org.flow.core.encryption.EncryptionConfig;
import org.flow.core.exceptions.InternalException;
import org.flow.core.exceptions.FlowRuntimeException;
import org.flow.core.metrics.MetricRegistry;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.TaskRun;
import org.flow.core.models.flows.FlowInterface;
import org.flow.core.models.flows.Input;
import org.flow.core.models.flows.Type;
import org.flow.core.models.property.PropertyContext;
import org.flow.core.models.tasks.Task;
import org.flow.core.models.triggers.AbstractTrigger;
import org.flow.core.plugins.PluginConfigurations;
import org.flow.core.services.ExecutionOutputService;
import org.flow.core.services.KVStoreService;
import org.flow.core.services.NamespaceService;
import org.flow.core.services.TaskOutputService;
import org.flow.core.storages.InternalStorage;
import org.flow.core.storages.NamespaceFactory;
import org.flow.core.storages.StorageContext;
import org.flow.core.storages.StorageInterface;

import org.flow.core.utils.ListUtils;
import io.micronaut.context.ApplicationContext;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import static org.flow.core.tenant.TenantService.MAIN_TENANT;

@Singleton
public class RunContextFactory {
    @Inject
    protected ApplicationContext applicationContext;

    @Inject
    protected PluginConfigurations pluginConfigurations;

    @Inject
    protected VariableRenderer variableRenderer;

    @Inject
    protected SecureVariableRendererFactory secureVariableRendererFactory;

    @Inject
    protected StorageInterface storageInterface;

    @Inject
    protected NamespaceService namespaceService;

    @Inject
    protected MetricRegistry metricRegistry;

    @Inject
    protected RunContextCache runContextCache;

    @Inject
    protected WorkingDirFactory workingDirFactory;

    @Inject
    protected EncryptionConfig encryptionConfig;

    @Inject
    protected FlowConfiguration flowConfiguration;

    @Inject
    protected RunContextLoggerFactory runContextLoggerFactory;

    @Inject
    protected KVStoreService kvStoreService;

    @Inject
    protected NamespaceFactory namespaceFactory;

    @Inject
    protected AssetManagerFactory assetManagerFactory;

    // Late injection: both read a repository, which a worker does not have, and a worker only ever
    // builds a run context from variables — never from an execution.
    @Inject
    protected Provider<TaskOutputService> taskOutputServiceProvider;

    @Inject
    protected Provider<ExecutionOutputService> executionOutputServiceProvider;

    @Inject
    protected Provider<RunContextInitializer> runContextInitializerProvider;

    // hacky
    public RunContextInitializer initializer() {
        return runContextInitializerProvider.get();
    }

    public RunContext of(FlowInterface flow, Execution execution) {
        return of(flow, execution, Function.identity());
    }

    public RunContext of(FlowInterface flow, Execution execution, boolean decryptVariable) {
        return of(flow, execution, Function.identity(), decryptVariable);
    }

    public RunContext of(FlowInterface flow, Execution execution, Function<RunVariables.Builder, RunVariables.Builder> runVariableModifier) {
        return of(flow, execution, runVariableModifier, true);
    }

    public RunContext of(FlowInterface flow, Execution execution, Function<RunVariables.Builder, RunVariables.Builder> runVariableModifier, boolean decryptVariables) {
        RunContextLogger runContextLogger = runContextLoggerFactory.create(execution);

        VariableRenderer variableRenderer = decryptVariables ? this.variableRenderer : secureVariableRendererFactory.createOrGet();

        RunVariables.Builder runVariablesBuilder = runVariableModifier.apply(
            newRunVariablesBuilder()
                .withFlow(flow)
                .withExecution(execution)
                .withOutputs(taskOutputServiceProvider.get().computeOutputs(execution))
                .withExecutionOutputs(executionOutputs(flow, execution))
        );
        Map<String, Object> variables = runVariablesBuilder.build(runContextLogger, PropertyContext.create(variableRenderer));

        return newBuilder()
            // Logger
            .withLogger(runContextLogger)
            // Execution
            .withPluginConfiguration(Map.of())
            .withStorage(new InternalStorage(runContextLogger.logger(), StorageContext.forExecution(execution), storageInterface, namespaceService, namespaceFactory))
            .withVariableRenderer(variableRenderer)
            .withVariables(variables)
            .withDecryptVariables(decryptVariables)
            .build();
    }

    public RunContext of(FlowInterface flow, Task task, Execution execution, TaskRun taskRun) {
        return this.of(flow, task, execution, taskRun, true);
    }

    public RunContext of(FlowInterface flow, Task task, Execution execution, TaskRun taskRun, boolean decryptVariables) {
        return this.of(flow, task, execution, taskRun, decryptVariables, this.variableRenderer);
    }

    public RunContext of(FlowInterface flow, Task task, Execution execution, TaskRun taskRun, boolean decryptVariables, VariableRenderer variableRenderer) {
        RunContextLogger runContextLogger = runContextLoggerFactory.create(taskRun, task, execution.getKind());

        RunVariables.Builder runVariablesBuilder = newRunVariablesBuilder()
            .withFlow(flow)
            .withTask(task)
            .withExecution(execution)
            .withOutputs(taskOutputServiceProvider.get().computeOutputs(execution))
            .withExecutionOutputs(executionOutputs(flow, execution))
            .withTaskRun(taskRun);
        Map<String, Object> variables = runVariablesBuilder.build(runContextLogger, PropertyContext.create(variableRenderer));

        return newBuilder()
            // Logger
            .withLogger(runContextLogger)
            // Task
            .withPluginConfiguration(pluginConfigurations.getConfigurationByPluginTypeOrAliases(task.getType(), task.getClass()))
            .withStorage(new InternalStorage(runContextLogger.logger(), StorageContext.forTask(taskRun), storageInterface, namespaceService, namespaceFactory))
            .withVariables(variables)
            .withDecryptVariables(decryptVariables)
            .withTask(task)
            .withVariableRenderer(variableRenderer)
            .build();
    }

    public RunContext of(FlowInterface flow, AbstractTrigger trigger) {
        RunContextLogger runContextLogger = runContextLoggerFactory.create(flow, trigger);
        return newBuilder()
            // Logger
            .withLogger(runContextLogger)
            // Task
            .withPluginConfiguration(pluginConfigurations.getConfigurationByPluginTypeOrAliases(trigger.getType(), trigger.getClass()))
            .withVariables(
                newRunVariablesBuilder()
                    .withFlow(flow)
                    .withTrigger(trigger)
                    .build(runContextLogger, PropertyContext.create(this.variableRenderer))
            )
            .withTrigger(trigger)
            .build();
    }

    public RunContext of(final FlowInterface flow, final Map<String, Object> variables) {
        RunContextLogger runContextLogger = new RunContextLogger();
        return newBuilder()
            .withLogger(runContextLogger)
            .withStorage(new InternalStorage(runContextLogger.logger(), StorageContext.forFlow(flow), storageInterface, namespaceService, namespaceFactory))
            .withVariables(
                newRunVariablesBuilder()
                    .withFlow(flow)
                    .withVariables(variables)
                    .build(runContextLogger, PropertyContext.create(this.variableRenderer))
            )
            .build();
    }

    @VisibleForTesting
    public RunContext of(final Map<String, Object> variables) {
        return of((Task) null, variables);
    }

    @VisibleForTesting
    public RunContext of(final Task task, final Map<String, Object> variables) {
        RunContextLogger runContextLogger = new RunContextLogger();
        return newBuilder()
            .withLogger(runContextLogger)
            .withStorage(
                new InternalStorage(
                    runContextLogger.logger(),
                    new StorageContext() {
                        @Override
                        public URI getContextStorageURI() {
                            return URI.create("");
                        }

                        @SuppressWarnings("unchecked")
                        @Override
                        public String getTenantId() {
                            var tenantId = ((Map<String, Object>) variables.getOrDefault("flow", Map.of())).get("tenantId");
                            return Optional.ofNullable(tenantId).map(Object::toString).orElse(MAIN_TENANT);
                        }

                        @SuppressWarnings("unchecked")
                        @Override
                        public String getNamespace() {
                            var namespace = ((Map<String, Object>) variables.getOrDefault("flow", Map.of())).get("namespace");
                            return Optional.ofNullable(namespace).map(Object::toString).orElse(null);
                        }
                    },
                    storageInterface,
                    namespaceService,
                    namespaceFactory
                )
            )
            .withVariables(variables)
            .withTask(task)
            .build();
    }

    @VisibleForTesting
    public RunContext of() {
        return of(Map.of());
    }

    /**
     * Loads the flow-level outputs of an execution. For a loop sub-execution, the outputs of the parent execution are
     * used as the parent is the execution exposed inside the run variables.
     *
     * @return the execution outputs or null if no outputs are defined in the flow or already computed in the execution
     */
    private Map<String, Object> executionOutputs(FlowInterface flow, Execution execution) {
        // fast path for no outputs defined in the flow
        if (flow != null && ListUtils.isEmpty(flow.getOutputs())) {
            return null;
        }

        Execution realExecution = execution != null && execution.getLoopRun() != null ? execution.getLoopRun().parent() : execution;

        try {
            return executionOutputServiceProvider.get().getOutputs(realExecution);
        } catch (InternalException e) {
            throw new FlowRuntimeException(e);
        }
    }


    private DefaultRunContext.Builder newBuilder() {
        return new DefaultRunContext.Builder()
            // inject mandatory services and config
            .withApplicationContext(applicationContext) // TODO - ideally application should not be injected here
            .withMeterRegistry(metricRegistry)
            .withVariableRenderer(this.variableRenderer)
            .withStorageInterface(storageInterface)
            .withSecretKey(encryptionConfig.asOptional())
            .withWorkingDir(workingDirFactory.createWorkingDirectory())
            .withKvStoreService(kvStoreService)
            .withAssetManagerFactory(assetManagerFactory);
    }

    protected RunVariables.Builder newRunVariablesBuilder() {
        return new RunVariables.DefaultBuilder(encryptionConfig.asOptional())
            .withEnvs(runContextCache.getEnvVars())
            .withGlobals(runContextCache.getGlobalVars())
            .withFlowConfiguration(
                new RunVariables.FlowConfiguration(
                    flowConfiguration.environment() != null ? flowConfiguration.environment().name() : null,
                    flowConfiguration.url()
                )
            );
    }
}
