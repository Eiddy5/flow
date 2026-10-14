package org.flow.worker;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.awaitility.core.ConditionTimeoutException;

import org.flow.core.metrics.MetricRegistry;
import org.flow.core.runners.WorkerJob;
import org.flow.core.server.AbstractService;
import org.flow.core.server.Metric;
import org.flow.core.server.ServerConfig;
import org.flow.core.server.ServiceStateChangeEvent;
import org.flow.core.server.ServiceType;
import org.flow.core.services.MaintenanceService;
import org.flow.core.utils.Await;
import org.flow.core.utils.Disposable;
import org.flow.core.worker.WorkerGroups;
import org.flow.core.worker.models.WorkerContext;
import org.flow.worker.fetchers.JobFetcher;
import org.flow.worker.queues.MonitoredWorkerQueue;
import org.flow.worker.senders.WorkerIOSender;

import io.micronaut.context.event.ApplicationEventPublisher;
import lombok.extern.slf4j.Slf4j;

import static org.flow.core.server.Service.ServiceState.TERMINATED_FORCED;
import static org.flow.core.server.Service.ServiceState.TERMINATED_GRACEFULLY;

/**
 * Common base class for worker-like services that:
 * <ul>
 * <li>fetch worker jobs through a {@link JobFetcher} (direct
 * queue subscription);</li>
 * <li>execute them through a per-instance {@link WorkerJobExecutor};</li>
 * <li>forward emitted events (results, logs, metrics) through a list of
 * {@link WorkerIOSender}s (durable queue senders);</li>
 * <li>react to maintenance mode by pausing / resuming the fetcher.</li>
 * </ul>
 * <p>
 * Subclasses customize <em>which</em> fetcher and which IO senders are used,
 * and how the effective {@code workerGroupId} for the {@link WorkerContext} is
 * resolved (default for {@link WorkerAgent}, system for the {@code SystemWorker}).
 */
@Slf4j
public abstract class AbstractWorker extends AbstractService {

    // Keeps the shutdown wait outliving the executor shutdown it observes, so the two deadlines never
    // expire together and race on the resulting state. Must stay above the wait's poll interval.
    private static final Duration FORCED_SHUTDOWN_MARGIN = Duration.ofSeconds(5);

    protected final MetricRegistry metricRegistry;
    protected final ServerConfig serverConfig;
    protected final MaintenanceService maintenanceService;

    protected final WorkerJobExecutor workerJobExecutor;
    protected final JobFetcher jobFetcher;
    protected final List<WorkerIOSender> workerIOSenders;
    protected final ExecutorService workerIOThreadsExecutor;

    protected final AtomicBoolean skipGracefulTermination = new AtomicBoolean(false);

    // Thread-safe: populated by start() but iterated from doStop()/stopNow() on other threads.
    protected final List<Disposable> disposables = new CopyOnWriteArrayList<>();

    protected String workerGroupId;

    // Single per-Worker meter deriving the task completion rate from worker.ended.count.
    private RateMeter rateMeter;

    protected AbstractWorker(
        final ServiceType serviceType,
        final ApplicationEventPublisher<ServiceStateChangeEvent> eventPublisher,
        final WorkerJobExecutor workerJobExecutor,
        final JobFetcher jobFetcher,
        final List<? extends WorkerIOSender> workerIOSenders,
        final MaintenanceService maintenanceService,
        final MetricRegistry metricRegistry,
        final ServerConfig serverConfig,
        final String ioThreadNamePrefix) {
        super(serviceType, eventPublisher);
        this.workerJobExecutor = workerJobExecutor;
        this.jobFetcher = jobFetcher;
        this.workerIOSenders = List.copyOf(workerIOSenders);
        this.maintenanceService = maintenanceService;
        this.metricRegistry = metricRegistry;
        this.serverConfig = serverConfig;
        this.workerIOThreadsExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name(ioThreadNamePrefix, 0).factory()
        );
    }

    /**
     * Resolve the Worker Queue id used to build the {@link WorkerContext}.
     * <p>
     * Called once during {@link #start(int)} to select the queue routing key.
     *
     * @return the resolved Worker Queue id; never null.
     */
    protected abstract String resolveWorkerGroupId();

    /**
     * Returns how many fetched jobs this worker buffers while all its threads are busy, which also bounds the
     * permits it advertises. Defaults to the thread count.
     */
    protected int jobBufferSize(int numThreads) {
        return numThreads;
    }

    /**
     * Starts the worker.
     */
    public void start(int numThreads) {
        guardedStart(() -> doStart(numThreads), () ->
        {
            setState(maintenanceService.isInMaintenanceMode() ? ServiceState.MAINTENANCE : ServiceState.RUNNING);
            log.info("Worker started with {} thread(s)", numThreads);
        });
    }

    private void doStart(int numThreads) {
        this.workerGroupId = resolveWorkerGroupId();

        this.setState(ServiceState.CREATED);

        int jobBufferSize = jobBufferSize(numThreads);

        // create metrics to store thread count, pending jobs and running jobs, so we can have autoscaling easily
        this.metricRegistry.gauge(
            MetricRegistry.METRIC_WORKER_JOB_THREAD_COUNT,
            MetricRegistry.METRIC_WORKER_JOB_THREAD_COUNT_DESCRIPTION,
            numThreads,
            metricRegistry.workerGroupTags(workerGroupId)
        );
        // Total in-flight capacity includes executing threads and buffered jobs.
        this.metricRegistry.gauge(
            MetricRegistry.METRIC_WORKER_MAX_CONCURRENCY,
            MetricRegistry.METRIC_WORKER_MAX_CONCURRENCY_DESCRIPTION,
            numThreads + jobBufferSize,
            metricRegistry.workerGroupTags(workerGroupId)
        );
        // Tasks-completed throughput (tasks/s), surfaced in the Worker Group UI. The
        // rate is derived from the existing worker.ended.count counter and sampled on
        // each heartbeat (see getMetrics) — no extra metric is registered.
        this.rateMeter = new RateMeter(metricRegistry, workerGroupId);

        WorkerContext workerContext = new WorkerContext(getId(), workerGroupId, numThreads, jobBufferSize);

        disposables.add(maintenanceService.listen(new MaintenanceService.MaintenanceListener() {
            @Override
            public void onMaintenanceModeEnter() {
                enterMaintenance();
            }

            @Override
            public void onMaintenanceModeExit() {
                exitMaintenance();
            }
        }));

        // Initialize and start all WorkerIO senders
        workerIOSenders.forEach(sender -> sender.init(workerContext));
        workerIOSenders.forEach(workerIOThreadsExecutor::submit);

        workerJobExecutor.start(workerContext, jobFetcher);

        boolean inMaintenanceMode = maintenanceService.isInMaintenanceMode();
        if (inMaintenanceMode) {
            // In maintenance mode, do not fetch new jobs but accept the ones already in flight
            jobFetcher.pause();
        }

        // Don't start fetching when a stop is already pending.
        if (isStopRequested()) {
            log.info("Worker stop is pending, not starting the job fetcher");
            return;
        }
        jobFetcher.init(workerContext);
        workerIOThreadsExecutor.submit(jobFetcher);
    }

    private void enterMaintenance() {
        this.jobFetcher.pause();
        this.workerJobExecutor.stopRealtimeTriggers();
        this.setState(ServiceState.MAINTENANCE);
    }

    private void exitMaintenance() {
        this.jobFetcher.resume();
        this.setState(ServiceState.RUNNING);
    }

    /**
     * Gets the list of jobs currently running.
     */
    public List<WorkerJob> getRunningJobs() {
        return workerJobExecutor.getRunningJobs();
    }

    @Override
    public Set<Metric> getMetrics() {
        if (this.metricRegistry == null) {
            return Collections.emptySet();
        }

        Stream<String> metrics = Stream.of(
            MetricRegistry.METRIC_WORKER_JOB_THREAD_COUNT,
            MetricRegistry.METRIC_WORKER_MAX_CONCURRENCY,
            MetricRegistry.METRIC_WORKER_RUNNING_COUNT,
            MonitoredWorkerQueue.QUEUE_SIZE,
            MonitoredWorkerQueue.QUEUE_REMAINING_CAPACITY
        );

        String ownGroup = WorkerGroups.normalize(this.workerGroupId);
        // Only expose worker-level (global) gauges in the heartbeat.
        Set<Metric> result = metrics
            .flatMap(metric -> metricRegistry.findGauges(metric).stream())
            .filter(gauge -> ownGroup.equals(gauge.getId().getTag(MetricRegistry.TAG_WORKER_GROUP)))
            .filter(
                gauge -> gauge.getId().getTag(MetricRegistry.TAG_TENANT_ID) == null
                    && gauge.getId().getTag(MetricRegistry.TAG_NAMESPACE_ID) == null
            )
            .map(Metric::of)
            .collect(Collectors.toSet());

        // Task completion rate (tasks/s). Sampled here rather than registered as a
        // Micrometer gauge — the heartbeat is the only consumer, and getMetrics() is
        // serialized by the liveness manager so the meter is sampled single-threaded.
        if (rateMeter != null) {
            result.add(
                new Metric(
                    MetricRegistry.METRIC_WORKER_TASKS_RATE,
                    "GAUGE",
                    MetricRegistry.METRIC_WORKER_TASKS_RATE_DESCRIPTION,
                    null,
                    List.of(new Metric.Tag(MetricRegistry.TAG_WORKER_GROUP, ownGroup)),
                    rateMeter.sampleRatePerSecond()
                )
            );
        }

        return result;
    }

    @Override
    protected Map<String, Object> getProperties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put(WorkerGroups.SERVICE_PROPS_KEY, workerGroupId);
        return properties;
    }

    @Override
    protected ServiceState doStop() {
        disposables.forEach(Disposable::dispose);

        // Stop fetching new jobs
        this.jobFetcher.stop(Duration.ZERO);

        // Pause the executor so no new jobs are started while we wind down
        this.workerJobExecutor.pause();

        final boolean terminatedGracefully;
        if (!skipGracefulTermination.get()) {
            terminatedGracefully = waitForJobsCompletion(serverConfig.terminationGracePeriod());
        } else {
            log.info("Terminating now and skip waiting for job completions.");
            this.workerJobExecutor.shutdownNow();
            terminatedGracefully = false;
        }

        stopAllWorkerIOThreads();

        if (terminatedGracefully && workerIOSenders.stream().anyMatch(WorkerIOSender::hasUndeliveredResults)) {
            log.warn("Some job results may not have reached the durable queue, so the worker reports a forced termination rather than a graceful one.");
            return TERMINATED_FORCED;
        }
        return terminatedGracefully ? TERMINATED_GRACEFULLY : TERMINATED_FORCED;
    }

    /**
     * Stop the worker immediately, without waiting for job completion.
     */
    public void stopNow() {
        log.info("Stopping now");
        this.disposables.forEach(Disposable::dispose);

        this.jobFetcher.stop(Duration.ZERO);
        stopAllWorkerIOThreads();
        this.workerJobExecutor.shutdownNow();
        log.info("Stopped");
    }

    private void stopAllWorkerIOThreads() {
        workerIOSenders.forEach(WorkerIOSender::stop);

        workerIOThreadsExecutor.shutdown();
        try {
            if (!workerIOThreadsExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("WorkerIO senders did not terminate gracefully, forcing shutdown");
                workerIOThreadsExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.error("Interrupted while waiting for Worker IO thread termination", e);
            workerIOThreadsExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            jobFetcher.close();
        }
    }

    private boolean waitForJobsCompletion(final Duration timeout) {
        final Instant deadline = Instant.now().plus(timeout);
        final AtomicReference<ServiceState> shutdownState = new AtomicReference<>();

        Thread.ofVirtual().name("worker-shutdown").start(
            () ->
            {
                try {
                    long remaining = Math.max(0, Instant.now().until(deadline, ChronoUnit.MILLIS));
                    boolean gracefullyShutdown = this.workerJobExecutor.shutdown(Duration.ofMillis(remaining));
                    shutdownState.set(gracefullyShutdown ? TERMINATED_GRACEFULLY : TERMINATED_FORCED);
                } catch (InterruptedException e) {
                    log.error("Failed to shutdown. Thread was interrupted");
                    shutdownState.set(TERMINATED_FORCED);
                }
            }
        );

        try {
            Await.await()
                // Awaitility caps at 10s by default, and honouring the grace period is the whole point here.
                .atMost(timeout.plus(FORCED_SHUTDOWN_MARGIN))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(() ->
                {
                    ServiceState serviceState = shutdownState.get();
                    if (serviceState == TERMINATED_FORCED || serviceState == TERMINATED_GRACEFULLY) {
                        log.info("All worker jobs are terminated");
                        return true;
                    }

                    long runningJobs = this.workerJobExecutor.getRunningJobCount();
                    if (runningJobs == 0) {
                        log.info("All worker threads are terminated");
                    } else {
                        log.warn("Waiting for all worker job to terminate (remaining: {}).", runningJobs);
                    }
                    return false;
                });
        } catch (ConditionTimeoutException e) {
            // Returning instead of propagating keeps doStop() on its normal path, which still has the
            // Worker IO senders to flush.
            log.warn(
                "Worker job executor did not report back {} past its {} deadline, forcing termination "
                    + "({} job(s) still running).",
                FORCED_SHUTDOWN_MARGIN, timeout, this.workerJobExecutor.getRunningJobCount()
            );
            return false;
        }

        return shutdownState.get() == TERMINATED_GRACEFULLY;
    }

    /**
     * Specify whether to skip graceful termination on shutdown.
     */
    public void skipGracefulTermination(final boolean skipGracefulTermination) {
        this.skipGracefulTermination.set(skipGracefulTermination);
    }
}
