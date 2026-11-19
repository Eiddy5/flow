package org.flow.executor.handler;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.event.Level;

import org.flow.core.exceptions.FlowNotFoundException;
import org.flow.core.exceptions.InternalException;
import org.flow.core.killswitch.EvaluationType;
import org.flow.core.killswitch.KillSwitchService;
import org.flow.core.metrics.MetricRegistry;
import org.flow.core.models.executions.*;
import org.flow.core.models.flows.FlowId;
import org.flow.core.models.flows.FlowWithException;
import org.flow.core.models.flows.FlowWithSource;
import org.flow.core.models.flows.State;
import org.flow.core.models.flows.quota.Quota;
import org.flow.core.models.tasks.Task;
import org.flow.core.queues.DispatchQueueInterface;
import org.flow.core.queues.KeyedDispatchQueueInterface;
import org.flow.core.queues.QueueException;
import org.flow.core.runners.*;
import org.flow.core.services.QuotaService;
import org.flow.core.services.WorkerQueueService;
import org.flow.core.trace.Tracer;
import org.flow.core.trace.TracerFactory;
import org.flow.core.utils.TruthUtils;
import org.flow.core.worker.WorkerQueues;
import org.flow.executor.*;
import org.flow.executor.KillSwitchActionService;
import org.flow.plugin.core.flow.WorkingDirectory;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import static org.flow.core.utils.Rethrow.throwConsumer;

@Singleton
@Slf4j
public class ExecutionEventMessageHandler implements ExecutorMessageHandler<ExecutionEvent> {
    private final Clock clock;
    private final ExecutionStateStore executionStateStore;
    private final ExecutionDelayStateStore executionDelayStateStore;
    private final ExecutorService executorService;
    private final WorkerQueueService workerGroupService;
    private final QuotaService quotaService;
    private final FlowMetaStoreInterface flowMetaStore;
    private final KeyedDispatchQueueInterface<WorkerJobEvent> workerJobEventQueue;
    private final DispatchQueueInterface<SubflowExecutionResult> subflowExecutionResultQueue;
    private final DispatchQueueInterface<Execution> executionQueue;
    private final RunContextLoggerFactory runContextLoggerFactory;
    private final KillSwitchService killSwitchService;
    private final KillSwitchActionService killSwitchActionService;
    private final MetricRegistry metricRegistry;
    private final Tracer tracer;

    @Inject
    public ExecutionEventMessageHandler(
        ExecutionStateStore executionStateStore,
        ExecutionDelayStateStore executionDelayStateStore,
        ExecutorService executorService,
        WorkerQueueService workerGroupService,
        QuotaService quotaService,
        FlowMetaStoreInterface flowMetaStore,
        KeyedDispatchQueueInterface<WorkerJobEvent> workerJobEventQueue,
        DispatchQueueInterface<SubflowExecutionResult> subflowExecutionResultQueue,
        DispatchQueueInterface<Execution> executionQueue,
        RunContextLoggerFactory runContextLoggerFactory,
        KillSwitchService killSwitchService,
        KillSwitchActionService killSwitchActionService,
        MetricRegistry metricRegistry,
        TracerFactory tracerFactory,
        Clock clock) {
        this.clock = clock;
        this.executionStateStore = executionStateStore;
        this.executionDelayStateStore = executionDelayStateStore;
        this.executorService = executorService;
        this.workerGroupService = workerGroupService;
        this.quotaService = quotaService;
        this.flowMetaStore = flowMetaStore;
        this.workerJobEventQueue = workerJobEventQueue;
        this.subflowExecutionResultQueue = subflowExecutionResultQueue;
        this.executionQueue = executionQueue;
        this.runContextLoggerFactory = runContextLoggerFactory;
        this.killSwitchService = killSwitchService;
        this.killSwitchActionService = killSwitchActionService;
        this.metricRegistry = metricRegistry;
        this.tracer = tracerFactory.getTracer(DefaultExecutor.class, "EXECUTOR");
    }

    @Override
    public Optional<ExecutorContext> handle(ExecutionEvent message) {
        EvaluationType evaluationType = killSwitchService.evaluate(message);
        if (evaluationType != EvaluationType.PASS) {
            var execution = executionStateStore.findByIdWithoutAcl(message.executionId());
            if (execution != null && evaluationType.isKillSwitched(execution)) {
                killSwitchActionService.handle(evaluationType, execution.getTenantId(), execution.getId());
                return Optional.empty();
            }
        }

        return executionStateStore.lock(
            message.executionId(), execution -> tracer.inCurrentContext(
                execution,
                FlowId.uidWithoutRevision(execution),
                () ->
                {
                    try {
                        final FlowWithSource flow = flowMetaStore.findByExecutionForRuntime(execution).orElseThrow(() -> new FlowNotFoundException(execution));

                        // A flow that resolves to a FlowWithException (unparsable, or blocked at execution
                        // pre-flight) cannot be processed: fail the execution fast instead of leaving it stuck.
                        // Terminated executions are left untouched so a late flow change never rewrites them.
                        if (flow instanceof FlowWithException flowWithException) {
                            if (execution.getState().isTerminated()) {
                                return null;
                            }
                            IllegalStateException exception = new IllegalStateException(flowWithException.getException());
                            Span.current().recordException(exception).setStatus(StatusCode.ERROR);
                            Execution failedExecution = fail(execution, exception);
                            return new ExecutorContext(execution).withExecution(failedExecution, "flowWithException");
                        }

                        ExecutorContext executor = new ExecutorContext(execution, flow);

                        // schedule it for later if needed
                        if (execution.getState().getCurrent() == State.Type.CREATED && execution.getScheduleDate() != null && execution.getScheduleDate().isAfter(clock.instant())) {
                            ExecutionDelay executionDelay = ExecutionDelay.builder()
                                .executionId(executor.getExecution().getId())
                                .date(execution.getScheduleDate())
                                .state(State.Type.CREATED)
                                .delayType(ExecutionDelay.DelayType.RESUME_FLOW)
                                .build();
                            executionDelayStateStore.save(executionDelay);
                            return executor;
                        }

                        // process actions that must be done after the execution has been created
                        if ((execution.getState().getCurrent() == State.Type.CREATED || execution.getState().failedThenRestarted())) {
                            // handle quotas
                            Optional<Quota> quota = quotaService.checkAndIncrement(flow);
                            if (quota.isPresent()) {
                                // a quota is exceeded: stop the execution in the desired state
                                Execution newExecution = switch (quota.get().getBehavior()) {
                                    case FAIL -> {
                                        var failedExecution = execution
                                            .failedExecutionFromExecutor(new IllegalStateException("Execution is FAILED due to " + quota.get().getDuration() + " quota limit exceeded"));
                                        var logger = runContextLoggerFactory.create(execution);
                                        logger.emitLogs(failedExecution.logs());
                                        yield failedExecution.execution();
                                    }
                                    case CANCEL -> execution.withState(State.Type.CANCELLED);
                                };

                                metricRegistry
                                    .counter(
                                        MetricRegistry.METRIC_EXECUTOR_QUOTA_EXCEEDED_COUNT, MetricRegistry.METRIC_EXECUTOR_QUOTA_EXCEEDED_COUNT_DESCRIPTION, metricRegistry.tags(execution)
                                    )
                                    .increment();

                                return executor.withExecution(newExecution, "processQuotas");
                            }
                        }

                        // process the execution
                        if (log.isDebugEnabled()) {
                            executorService.log(log, true, executor);
                        }
                        executor = executorService.process(executor);

                        if (executor.getNextCount() > 0) {
                            executor.withExecution(
                                executorService.onNext(executor.getExecution(), executor.getNextCount()),
                                "onNext"
                            );
                        }

                        // worker task
                        if (!executor.getWorkerTasks().isEmpty()) {
                            List<WorkerTaskResult> workerTaskResults = new ArrayList<>();
                            executor
                                .getWorkerTasks()
                                .forEach(throwConsumer(executorTask ->
                                {
                                    WorkerTask workerTask = executorTask.workerTask();
                                    try {
                                        if (!TruthUtils.isTruthy(executorTask.runContext().render(workerTask.getTask().getRunIf()))) {
                                            workerTaskResults.add(
                                                new WorkerTaskResult(
                                                    workerTask.getTaskRun().withState(State.Type.SKIPPED)
                                                        .addAttempt(TaskRunAttempt.builder().state(new State().withState(State.Type.SKIPPED)).build())
                                                )
                                            );
                                        } else {
                                            if (workerTask.getTask().isSendToWorkerTask()) {
                                                Optional<WorkerQueueRouting> routing = workerGroupService.resolveWorkerQueueForJob(flow, workerTask);
                                                // Internal dispatch convention: null = default queue. SystemTask routing
                                                // is enforced upstream in WorkerQueueService.
                                                String workerQueueId = routing
                                                    .map(WorkerQueueRouting::workerQueueId)
                                                    .map(WorkerQueues::toDispatchKey)
                                                    .orElse(null);
                                                if (workerTask.getTask() instanceof WorkingDirectory) {
                                                    // WorkingDirectory is a flowable so it will be moved to RUNNING a few lines under
                                                    workerJobEventQueue.emit(workerQueueId, WorkerJobEvent.of(workerTask, workerQueueId));
                                                } else {
                                                    TaskRun taskRun = workerTask.getTaskRun().withState(State.Type.SUBMITTED);
                                                    workerJobEventQueue.emit(workerQueueId, WorkerJobEvent.of(workerTask.withTaskRun(taskRun), workerQueueId));
                                                    workerTaskResults.add(new WorkerTaskResult(taskRun));
                                                }
                                            }

                                            // flowable attempt state transition to running
                                            // Skip if the task was already terminated by handleChildWorkerTaskResult (e.g., empty Loop)
                                            if (workerTask.getTask().isFlowable() && !workerTask.getTaskRun().getState().isTerminated()) {
                                                List<TaskRunAttempt> attempts = Optional.ofNullable(workerTask.getTaskRun().getAttempts())
                                                    .map(ArrayList::new)
                                                    .orElseGet(ArrayList::new);

                                                attempts.add(
                                                    TaskRunAttempt.builder()
                                                        .state(new State().withState(State.Type.RUNNING))
                                                        .build()
                                                );

                                                TaskRun updatedTaskRun = workerTask.getTaskRun()
                                                    .withAttempts(attempts)
                                                    .withState(State.Type.RUNNING);

                                                workerTaskResults.add(new WorkerTaskResult(updatedTaskRun));
                                            }
                                        }
                                    } catch (Exception e) {
                                        workerTaskResults.add(new WorkerTaskResult(workerTask.getTaskRun().withState(State.Type.FAILED)));
                                        executorTask.runContext().logger()
                                            .error("Failed to evaluate the runIf condition for task {}. Cause: {}", workerTask.getTask().getId(), e.getMessage(), e);
                                    }
                                }));

                            try {
                                executorService.addWorkerTaskResults(executor, workerTaskResults);
                            } catch (InternalException e) {
                                log.error("Unable to add a worker task result to the execution", e);
                            }
                        }

                        // subflow execution results
                        if (!executor.getSubflowExecutionResults().isEmpty()) {
                            executor.getSubflowExecutionResults()
                                .forEach(throwConsumer(subflowExecutionResult ->
                                {
                                    subflowExecutionResultQueue.emit(subflowExecutionResult);
                                }));
                        }

                        // schedulerDelay
                        if (!executor.getExecutionDelays().isEmpty()) {
                            executor.getExecutionDelays()
                                .forEach(executionDelay -> executionDelayStateStore.save(executionDelay));
                        }

                        // subflow executions
                        if (!executor.getSubflowExecutions().isEmpty()) {
                            executor.getSubflowExecutions().forEach(throwConsumer(subflowExecution ->
                            {
                                Execution subExecution = subflowExecution.getExecution();
                                String msg = subExecution.getState().getCurrent() == State.Type.RESTARTED ? String.format(
                                    "Restarted execution [[link execution=\"%s\" flowId=\"%s\" namespace=\"%s\"]]", subExecution.getId(), subExecution.getFlowId(), subExecution.getNamespace()
                                )
                                    : String.format(
                                        "Created new execution [[link execution=\"%s\" flowId=\"%s\" namespace=\"%s\"]]", subExecution.getId(), subExecution.getFlowId(),
                                        subExecution.getNamespace()
                                    );

                                log.info(msg);

                                // Bind the logger to the parent task so its configured logLevel (e.g. plugin
                                // defaults setting logLevel: WARN) applies to the creation log, which is emitted
                                // directly and therefore bypasses the regular logging pipeline (#16238).
                                Task parentTask = flow.findTaskByTaskIdOrNull(subflowExecution.getParentTaskRun().getTaskId());
                                var logger = parentTask != null
                                    ? runContextLoggerFactory.create(subflowExecution.getParentTaskRun(), parentTask, subflowExecution.getExecution().getKind())
                                    : runContextLoggerFactory.create(execution);
                                logger.emitLogIfEnabled(
                                    LogEntry.of(subflowExecution.getParentTaskRun(), subflowExecution.getExecution().getKind()).toBuilder()
                                        .level(Level.INFO)
                                        .message(msg)
                                        .timestamp(subflowExecution.getParentTaskRun().getState().getStartDate())
                                        .thread(Thread.currentThread().getName())
                                        .build()
                                );

                                executionQueue.emit(subflowExecution.getExecution());
                            }));
                        }

                        // trigger new loop executions
                        if (!executor.getLoopExecutions().isEmpty()) {
                            executor.getLoopExecutions().forEach(throwConsumer(loopExecution -> executionQueue.emit(loopExecution)));
                        }

                        if (executor.getExecution().getState().getCurrent().isTerminatedInError()) {
                            Span.current().setStatus(StatusCode.ERROR, "Execution ended in state " + executor.getExecution().getState().getCurrent().name());
                        }
                        return executor;
                    } catch (QueueException e) {
                        Span.current().recordException(e).setStatus(StatusCode.ERROR);

                        Execution failedExecution = fail(execution, e);
                        return new ExecutorContext(execution).withExecution(failedExecution, "queueException");
                    } catch (FlowNotFoundException e) {
                        // avoid infinite for FlowNotFoundException
                        if (!execution.getState().getCurrent().isFailed()) {
                            Span.current().recordException(e).setStatus(StatusCode.ERROR);
                            Execution failedExecution = fail(execution, e);
                            return new ExecutorContext(execution).withExecution(failedExecution, "flowNotFound");
                        }

                        Span.current().recordException(e).setStatus(StatusCode.ERROR);
                        return null;
                    }
                }
            )
        );
    }

    private Execution fail(Execution message, Exception e) {
        var failedExecution = message.failedExecutionFromExecutor(e);
        var logger = runContextLoggerFactory.create(failedExecution.execution());
        logger.emitLogs(failedExecution.logs());
        return failedExecution.execution().getState().isFailed() ? failedExecution.execution() : failedExecution.execution().withState(State.Type.FAILED);
    }

}
