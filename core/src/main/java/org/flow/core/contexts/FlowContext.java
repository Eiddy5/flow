package org.flow.core.contexts;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.google.common.base.Suppliers;

import org.flow.core.models.ServerType;
import org.flow.core.plugins.PluginRegistry;
import org.flow.core.storages.StorageInterface;
import org.flow.core.utils.EditionProvider;
import org.flow.core.utils.EditionProvider.Edition;
import org.flow.core.utils.VersionProvider;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.context.env.PropertySource;
import lombok.extern.slf4j.Slf4j;

/**
 * Utility class for retrieving common information about a Flow Server at runtime.
 */
@Slf4j
@SuppressWarnings("this-escape")
public abstract class FlowContext {

    private static final AtomicReference<FlowContext> INSTANCE = new AtomicReference<>();

    // Properties
    public static final String FLOW_SERVER_TYPE = "flow.server-type";
    public static final String FLOW_ALLOCATED_CPU_CORES = "flow.allocated-cpu-cores";

    // Those properties are injected bases on the CLI args.
    private static final String FLOW_WORKER_MAX_NUM_THREADS = "flow.worker.max-num-threads";

    /**
     * Gets the current {@link FlowContext}.
     *
     * @return The context.
     * @throws IllegalStateException if not context is initialized.
     */
    public static FlowContext getContext() {
        return Optional.ofNullable(INSTANCE.get())
            .orElseThrow(() -> new IllegalStateException("Flow context not initialized"));
    }

    /**
     * Sets the current {@link FlowContext}.
     *
     * @param context The context.
     */
    public static void setContext(final FlowContext context) {
        FlowContext.INSTANCE.set(context);
    }

    /**
     * Returns the current {@link ServerType}.
     *
     * @return The {@link ServerType}.
     */
    public abstract ServerType getServerType();

    /**
     * Number of CPU cores allocated for the Flow process.
     * It defaults to the number of available CPU cores but can be overridden by setting {@link #FLOW_ALLOCATED_CPU_CORES} configuration property.
     * It is used everywhere we compute a number of threads based on the number of CPU cores instead of directly relying on the number of available CPU cores.
     */
    public abstract int getAllocatedCpuCores();

    public abstract Optional<Integer> getWorkerMaxNumThreads();

    public abstract void injectWorkerConfigs(Integer maxNumThreads);

    /**
     * Returns the Flow Version.
     *
     * @return the string version.
     */
    public abstract String getVersion();

    /**
     * Returns the edition (OSS or EE) this Flow server runs.
     *
     * @return the {@link Edition}.
     */
    public abstract Edition getEdition();

    /**
     * Returns the Flow Plugin Registry.
     *
     * @return the {@link PluginRegistry}.
     */
    public abstract PluginRegistry getPluginRegistry();

    public abstract StorageInterface getStorageInterface();

    /**
     * Returns the Micronaut active environments.
     */
    public abstract Set<String> getEnvironments();

    /**
     * Shutdowns the Flow application.
     * WARNING: this method is not safe to be called from the global FlowContext static instance as in unit test,
     * this instance may have already been replaced by a fresh one, so you might shut down the next test run!
     * The safe pattern is to inject a FlowContext instance and call this method on it.
     */
    public void shutdown() {
        // noop
    }

    /**
     * Flow context initializer
     */
    @Context
    @Requires(missingBeans = FlowContext.class)
    public static class Initializer extends FlowContext {

        private final ApplicationContext applicationContext;
        private final Environment environment;
        private final Supplier<String> version;
        private final Supplier<Edition> edition;

        private final AtomicBoolean isShutdown = new AtomicBoolean(false);

        /**
         * Creates a new {@link FlowContext} instance.
         *
         * @param applicationContext The {@link ApplicationContext}.
         * @param environment The {@link Environment}.
         */
        public Initializer(ApplicationContext applicationContext,
            Environment environment) {
            this.applicationContext = applicationContext;
            // Lazy init of the version
            this.version = Suppliers.memoize(() ->
            // VersionProvider is not always available, for example in unit tests, so we use Optional to avoid issues in those cases.
            Optional.ofNullable(applicationContext.getBean(VersionProvider.class)).map(VersionProvider::getVersion).orElse(null)
            );
            // Lazy init of the edition
            this.edition = Suppliers.memoize(() ->
            // EditionProvider is not always available, for example in unit tests, so we use Optional to avoid issues in those cases.
            Optional.ofNullable(applicationContext.getBean(EditionProvider.class)).map(EditionProvider::get).orElse(Edition.OSS)
            );
            this.environment = environment;
            FlowContext.setContext(this);
        }

        /** {@inheritDoc} **/
        @Override
        public ServerType getServerType() {
            return Optional.ofNullable(environment)
                .flatMap(env -> env.getProperty(FLOW_SERVER_TYPE, ServerType.class))
                .orElse(ServerType.STANDALONE);
        }

        @Override
        public int getAllocatedCpuCores() {
            return applicationContext.getProperty(FLOW_ALLOCATED_CPU_CORES, Integer.class)
                .orElse(Runtime.getRuntime().availableProcessors());
        }

        /** {@inheritDoc} **/
        @Override
        public Optional<Integer> getWorkerMaxNumThreads() {
            return Optional.ofNullable(environment)
                .flatMap(env -> env.getProperty(FLOW_WORKER_MAX_NUM_THREADS, Integer.class));
        }

        /** {@inheritDoc} **/
        @Override
        public void injectWorkerConfigs(Integer maxNumThreads) {
            final Map<String, Object> configs = new HashMap<>();
            Optional.ofNullable(maxNumThreads)
                .ifPresent(val -> configs.put(FLOW_WORKER_MAX_NUM_THREADS, val));

            if (!configs.isEmpty()) {
                environment.addPropertySource(PropertySource.of("flow-runtime", configs));
            }
        }

        /** {@inheritDoc} **/
        @Override
        public void shutdown() {
            if (isShutdown.compareAndSet(false, true)) {
                if (!applicationContext.isRunning()) {
                    log.info("Flow server - Shutdown already in progress, skipping");
                    return;
                }
                log.info("Flow server - Shutdown initiated");
                Optional<org.flow.core.runners.FlowEngine> engine = applicationContext.findBean(org.flow.core.runners.FlowEngine.class);
                if (engine.isPresent() && engine.get().hasStarted()) {
                    engine.get().requestStop();
                    log.info("Flow engine - Shutdown requested; application context remains running");
                } else {
                    applicationContext.close();
                    log.info("Flow server - Shutdown completed");
                }
            }
        }

        /** {@inheritDoc} **/
        @Override
        public String getVersion() {
            return version.get();
        }

        /** {@inheritDoc} **/
        @Override
        public Edition getEdition() {
            return edition.get();
        }

        /** {@inheritDoc} **/
        @Override
        public PluginRegistry getPluginRegistry() {
            if (this.applicationContext == null) {
                throw new IllegalStateException("ApplicationContext is null");
            }
            // Lazy init of the PluginRegistry.
            return this.applicationContext.getBean(PluginRegistry.class);
        }

        @Override
        public StorageInterface getStorageInterface() {
            if (this.applicationContext == null) {
                throw new IllegalStateException("ApplicationContext is null");
            }
            // Lazy init of the StorageInterface.
            return this.applicationContext.getBean(StorageInterface.class);
        }

        @Override
        public Set<String> getEnvironments() {
            return this.applicationContext.getEnvironment().getActiveNames();
        }
    }
}
