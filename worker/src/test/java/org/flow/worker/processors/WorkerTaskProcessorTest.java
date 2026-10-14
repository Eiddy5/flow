package org.flow.worker.processors;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.metrics.MetricRegistry;
import org.flow.core.models.assets.Asset;
import org.flow.core.models.assets.AssetIdentifier;
import org.flow.core.models.assets.AssetsDeclaration;
import org.flow.core.models.assets.Custom;
import org.flow.core.models.assets.External;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.LogEntry;
import org.flow.core.models.executions.TaskRun;
import org.flow.core.models.flows.Flow;
import org.flow.core.models.flows.State;
import org.flow.core.models.property.Property;
import org.flow.core.models.tasks.AssetFailureBehavior;
import org.flow.core.models.tasks.ResolvedTask;
import org.flow.core.models.tasks.RunnableTask;
import org.flow.core.models.tasks.Task;
import org.flow.core.models.tasks.VoidOutput;
import org.flow.core.queues.DispatchQueueInterface;
import org.flow.core.runners.RunContext;
import org.flow.core.runners.RunContextFactory;
import org.flow.core.runners.RunContextInitializer;
import org.flow.core.runners.RunContextLoggerFactory;
import org.flow.core.runners.Worker;
import org.flow.core.runners.WorkerTask;
import org.flow.core.runners.WorkerTaskData;
import org.flow.core.runners.WorkerTaskResult;
import org.flow.core.server.ServerConfig;
import org.flow.core.trace.TracerFactory;
import org.flow.core.utils.IdUtils;
import org.flow.core.utils.TestsUtils;
import org.flow.core.worker.WorkerGroups;
import org.flow.worker.WorkerSecurityService;
import org.flow.worker.queues.InMemoryWorkerQueue;
import org.flow.worker.queues.WorkerQueue;
import org.flow.worker.services.ExecutionKilledManager;

import jakarta.inject.Inject;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Component-level tests for {@link WorkerTaskProcessor}'s behavior when the worker is shutting down,
 * covering the regression from upstream regression #17124:
 * a task that fails on its own during the termination grace period must still have its terminal
 * result emitted, while a task the shutdown actually interrupted is deferred for resubmission.
 */
@FlowTest
class WorkerTaskProcessorTest {

    @Inject
    private ServerConfig serverConfig;

    @Inject
    private MetricRegistry metricRegistry;

    @Inject
    private WorkerSecurityService workerSecurityService;

    @Inject
    private TracerFactory tracerFactory;

    @Inject
    private RunContextInitializer runContextInitializer;

    @Inject
    private RunContextLoggerFactory runContextLoggerFactory;

    @Inject
    private ExecutionKilledManager executionKilledManager;

    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private DispatchQueueInterface<LogEntry> logQueue;

    @Test
    void shouldEmitFailedResultWhenTaskFailsOnItsOwnDuringShutdownDrain() throws Exception {
        // Given a processor in the graceful drain window (stopped) that did NOT interrupt the task
        InMemoryWorkerQueue<WorkerTaskResult> resultQueue = new InMemoryWorkerQueue<>(100);
        WorkerTaskProcessor processor = newProcessor(resultQueue);
        processor.stop();

        // When the task reaches a FAILED state on its own during the drain window
        processor.process(failingWorkerTask());

        // Then its terminal FAILED result is emitted, not silently dropped
        List<WorkerTaskResult> results = drain(resultQueue);
        assertThat(results)
            .as("a genuine failure during the drain window must be emitted, not dropped (#17124)")
            .anyMatch(result -> result.getTaskRun().getState().isFailed());
    }

    @Test
    void shouldDropFailedResultWhenTaskWasInterruptedByShutdown() throws Exception {
        // Given a processor whose task is forcibly interrupted by the shutdown
        InMemoryWorkerQueue<WorkerTaskResult> resultQueue = new InMemoryWorkerQueue<>(100);
        WorkerTaskProcessor processor = newProcessor(resultQueue);
        processor.signalShutdownInterrupt();
        processor.stop();

        // When the interrupted task ends in a failed state
        processor.process(failingWorkerTask());

        // Then no terminal result is emitted (it will be resubmitted) — only the RUNNING preamble
        List<WorkerTaskResult> results = drain(resultQueue);
        assertThat(results)
            .as("an interrupted task's failure must be deferred for resubmission, not reported")
            .noneMatch(result -> result.getTaskRun().getState().isFailed());
    }

    @Test
    void shouldKeepDeclaredInputsWhenOutputsCannotBeRendered() throws Exception {
        // Given a task whose declared outputs cannot be rendered, while its declared inputs can
        InMemoryWorkerQueue<WorkerTaskResult> resultQueue = new InMemoryWorkerQueue<>(100);
        WorkerTaskProcessor processor = newProcessor(resultQueue);

        // When the task runs
        processor.process(unrenderableOutputsWorkerTask());

        // Then the inputs are still emitted, and the unrendered outputs only show up in the state
        List<WorkerTaskResult> results = drain(resultQueue);
        TaskRun taskRun = results.getLast().getTaskRun();
        assertThat(taskRun.getAssetEmits())
            .as("a failing output must not discard the declared inputs")
            .singleElement()
            .satisfies(bundle ->
            {
                assertThat(bundle.getInputs()).extracting(AssetIdentifier::id).containsExactly("declared-input");
                assertThat(bundle.getOutputs()).isEmpty();
            });
        assertThat(taskRun.getState().getCurrent())
            .as("a partially emitted declaration is still an emission failure, escalated per assetFailureBehavior")
            .isEqualTo(State.Type.WARNING);
    }

    @Test
    void shouldDefaultInputNamespaceButLeaveOutputNamespaceNullWhenNotDeclared() throws Exception {
        // Given a task declaring an input and an output that carry no namespace
        InMemoryWorkerQueue<WorkerTaskResult> resultQueue = new InMemoryWorkerQueue<>(100);
        WorkerTaskProcessor processor = newProcessor(resultQueue);

        // When the task runs
        processor.process(namespacelessAssetsWorkerTask());

        // Then the input inherits the flow's namespace, but the output namespace is left null so
        // Asset#toUpdated can keep the stored asset's own namespace instead of defaulting it here
        List<WorkerTaskResult> results = drain(resultQueue);
        TaskRun taskRun = results.getLast().getTaskRun();
        assertThat(taskRun.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        assertThat(taskRun.getAssetEmits())
            .singleElement()
            .satisfies(bundle ->
            {
                assertThat(bundle.getInputs()).extracting(AssetIdentifier::namespace).containsExactly("org.flow.unit-test");
                assertThat(bundle.getOutputs()).extracting(Asset::getNamespace).containsExactly((String) null);
            });
    }

    @Test
    void shouldKeepSuccessWhenAssetEmissionFailsAndBehaviorIsIgnore() throws Exception {
        InMemoryWorkerQueue<WorkerTaskResult> resultQueue = new InMemoryWorkerQueue<>(100);
        WorkerTaskProcessor processor = newProcessor(resultQueue);
        List<LogEntry> logs = new CopyOnWriteArrayList<>();
        logQueue.addListener(logs::add);

        processor.process(assetEmissionFailureWorkerTask(AssetFailureBehavior.IGNORE));

        List<WorkerTaskResult> results = drain(resultQueue);
        String taskRunId = results.getLast().getTaskRun().getId();
        assertThat(results.getLast().getTaskRun().getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        // state is unchanged, but the failed asset emission is still surfaced to the user
        LogEntry escalationLog = TestsUtils.awaitLog(logs, log -> taskRunId.equals(log.getTaskRunId()) && log.getMessage().contains("assetFailureBehavior"));
        assertThat(escalationLog).isNotNull();
        assertThat(escalationLog.getMessage()).contains("not changed").contains("IGNORE");
    }

    @Test
    void shouldClampToWarningWhenAssetFailureBehaviorIsFailAndAllowFailureIsSet() throws Exception {
        InMemoryWorkerQueue<WorkerTaskResult> resultQueue = new InMemoryWorkerQueue<>(100);
        WorkerTaskProcessor processor = newProcessor(resultQueue);

        processor.process(assetEmissionFailureWorkerTask(AssetFailureBehavior.FAIL, true, false));

        List<WorkerTaskResult> results = drain(resultQueue);
        // allowFailure applies uniformly to a FAILED state, whether genuine or escalated by assetFailureBehavior
        assertThat(results.getLast().getTaskRun().getState().getCurrent()).isEqualTo(State.Type.WARNING);
    }

    @Test
    void shouldClampToSuccessWhenAssetFailureBehaviorIsWarnAndAllowWarningIsSet() throws Exception {
        InMemoryWorkerQueue<WorkerTaskResult> resultQueue = new InMemoryWorkerQueue<>(100);
        WorkerTaskProcessor processor = newProcessor(resultQueue);
        List<LogEntry> logs = new CopyOnWriteArrayList<>();
        logQueue.addListener(logs::add);

        processor.process(assetEmissionFailureWorkerTask(AssetFailureBehavior.WARN, false, true));

        List<WorkerTaskResult> results = drain(resultQueue);
        String taskRunId = results.getLast().getTaskRun().getId();
        // allowWarning has final say: a WARNING escalated purely from assetFailureBehavior is clamped down
        assertThat(results.getLast().getTaskRun().getState().getCurrent()).isEqualTo(State.Type.SUCCESS);
        // the final state (SUCCESS) alone can't tell escalation-then-clamp apart from no escalation at
        // all — the log line is what actually proves assetFailureBehavior ran
        LogEntry escalationLog = TestsUtils.awaitLog(logs, log -> taskRunId.equals(log.getTaskRunId()) && log.getMessage().contains("assetFailureBehavior"));
        assertThat(escalationLog).isNotNull();
        assertThat(escalationLog.getMessage()).contains("SUCCESS").contains("WARNING").contains("WARN");
    }

    @Test
    void shouldNotEscalateWhenTaskAlreadyFailedOnItsOwn() throws Exception {
        InMemoryWorkerQueue<WorkerTaskResult> resultQueue = new InMemoryWorkerQueue<>(100);
        WorkerTaskProcessor processor = newProcessor(resultQueue);
        List<LogEntry> logs = new CopyOnWriteArrayList<>();
        logQueue.addListener(logs::add);

        processor.process(taskFailsAndAssetEmissionFailsWorkerTask(AssetFailureBehavior.WARN));

        List<WorkerTaskResult> results = drain(resultQueue);
        String taskRunId = results.getLast().getTaskRun().getId();
        // a task that already terminated in error on its own is not touched by assetFailureBehavior
        assertThat(results.getLast().getTaskRun().getState().getCurrent()).isEqualTo(State.Type.FAILED);
        LogEntry escalationLog = TestsUtils.awaitLog(logs, log -> taskRunId.equals(log.getTaskRunId()) && log.getMessage().contains("assetFailureBehavior"));
        assertThat(escalationLog).isNotNull();
        assertThat(escalationLog.getMessage()).contains("not changed");
    }

    @Test
    void shouldClampToWarningWhenTaskAlreadyFailedOnItsOwnAndAllowFailureIsSet() throws Exception {
        InMemoryWorkerQueue<WorkerTaskResult> resultQueue = new InMemoryWorkerQueue<>(100);
        WorkerTaskProcessor processor = newProcessor(resultQueue);

        processor.process(taskFailsAndAssetEmissionFailsWorkerTask(AssetFailureBehavior.FAIL, true));

        List<WorkerTaskResult> results = drain(resultQueue);
        // a genuine pre-existing failure is untouched by assetFailureBehavior (apply() no-ops on an already
        // terminated-in-error state), so allowFailure still applies its ordinary softening here
        assertThat(results.getLast().getTaskRun().getState().getCurrent()).isEqualTo(State.Type.WARNING);
    }

    @Test
    void shouldFailTaskWhenAssetEmissionFailsAndBehaviorIsFail() throws Exception {
        InMemoryWorkerQueue<WorkerTaskResult> resultQueue = new InMemoryWorkerQueue<>(100);
        WorkerTaskProcessor processor = newProcessor(resultQueue);
        List<LogEntry> logs = new CopyOnWriteArrayList<>();
        logQueue.addListener(logs::add);

        processor.process(assetEmissionFailureWorkerTask(AssetFailureBehavior.FAIL));

        List<WorkerTaskResult> results = drain(resultQueue);
        String taskRunId = results.getLast().getTaskRun().getId();
        assertThat(results.getLast().getTaskRun().getState().getCurrent()).isEqualTo(State.Type.FAILED);
        // the user must be able to tell FAILED came from the asset emission, not the task's own logic
        LogEntry escalationLog = TestsUtils.awaitLog(logs, log -> taskRunId.equals(log.getTaskRunId()) && log.getMessage().contains("assetFailureBehavior"));
        assertThat(escalationLog).isNotNull();
        assertThat(escalationLog.getMessage()).contains("SUCCESS").contains("FAILED").contains("FAIL");
    }

    @Test
    void shouldWarnTaskWhenAssetEmissionFailsAndBehaviorIsWarn() throws Exception {
        InMemoryWorkerQueue<WorkerTaskResult> resultQueue = new InMemoryWorkerQueue<>(100);
        WorkerTaskProcessor processor = newProcessor(resultQueue);
        List<LogEntry> logs = new CopyOnWriteArrayList<>();
        logQueue.addListener(logs::add);

        processor.process(assetEmissionFailureWorkerTask(AssetFailureBehavior.WARN));

        List<WorkerTaskResult> results = drain(resultQueue);
        String taskRunId = results.getLast().getTaskRun().getId();
        assertThat(results.getLast().getTaskRun().getState().getCurrent()).isEqualTo(State.Type.WARNING);
        LogEntry escalationLog = TestsUtils.awaitLog(logs, log -> taskRunId.equals(log.getTaskRunId()) && log.getMessage().contains("assetFailureBehavior"));
        assertThat(escalationLog).isNotNull();
        assertThat(escalationLog.getMessage()).contains("SUCCESS").contains("WARNING").contains("WARN");
    }

    // flow-ee#10347: a declared asset with no id fails only its own task, outright rather than per
    // assetFailureBehavior, since a malformed declaration cannot succeed on a retry or a later execution.
    @Test
    void shouldFailTaskWhenAssetOutputHasNoId() throws Exception {
        InMemoryWorkerQueue<WorkerTaskResult> resultQueue = new InMemoryWorkerQueue<>(100);
        WorkerTaskProcessor processor = newProcessor(resultQueue);
        List<LogEntry> logs = new CopyOnWriteArrayList<>();
        logQueue.addListener(logs::add);

        processor.process(assetOutputMissingIdWorkerTask(AssetFailureBehavior.WARN));

        List<WorkerTaskResult> results = drain(resultQueue);
        String taskRunId = results.getLast().getTaskRun().getId();
        assertThat(results.getLast().getTaskRun().getState().getCurrent()).isEqualTo(State.Type.FAILED);
        assertThat(results.getLast().getTaskRun().getAssetEmits()).isNullOrEmpty();
        LogEntry errorLog = TestsUtils.awaitLog(logs, log -> taskRunId.equals(log.getTaskRunId()) && log.getMessage() != null && log.getMessage().startsWith("Invalid asset declaration"));
        assertThat(errorLog).isNotNull();
        assertThat(errorLog.getMessage()).contains("assets.outputs[0].id").contains("must not be blank");
    }

    @Test
    void shouldFailTaskWhenAssetInputHasNoId() throws Exception {
        InMemoryWorkerQueue<WorkerTaskResult> resultQueue = new InMemoryWorkerQueue<>(100);
        WorkerTaskProcessor processor = newProcessor(resultQueue);
        List<LogEntry> logs = new CopyOnWriteArrayList<>();
        logQueue.addListener(logs::add);

        processor.process(assetInputMissingIdWorkerTask(AssetFailureBehavior.WARN));

        List<WorkerTaskResult> results = drain(resultQueue);
        String taskRunId = results.getLast().getTaskRun().getId();
        assertThat(results.getLast().getTaskRun().getState().getCurrent()).isEqualTo(State.Type.FAILED);
        assertThat(results.getLast().getTaskRun().getAssetEmits()).isNullOrEmpty();
        LogEntry errorLog = TestsUtils.awaitLog(logs, log -> taskRunId.equals(log.getTaskRunId()) && log.getMessage() != null && log.getMessage().startsWith("Invalid asset declaration"));
        assertThat(errorLog).isNotNull();
        assertThat(errorLog.getMessage()).contains("assets.inputs[0].id").contains("must not be blank");
    }

    @Test
    void shouldFailTaskWhenAssetOutputHasNoIdEvenWhenBehaviorIsIgnore() throws Exception {
        InMemoryWorkerQueue<WorkerTaskResult> resultQueue = new InMemoryWorkerQueue<>(100);
        WorkerTaskProcessor processor = newProcessor(resultQueue);

        // IGNORE would otherwise silently drop a soft asset-emission failure (see
        // shouldKeepSuccessWhenAssetEmissionFailsAndBehaviorIsIgnore); a malformed declaration
        // must not be swallowed the same way — this is the exact regression the issue reports.
        processor.process(assetOutputMissingIdWorkerTask(AssetFailureBehavior.IGNORE));

        List<WorkerTaskResult> results = drain(resultQueue);
        assertThat(results.getLast().getTaskRun().getState().getCurrent()).isEqualTo(State.Type.FAILED);
    }

    @Test
    void shouldNotRewriteAKilledTaskWhenItsAssetDeclarationIsInvalid() throws Exception {
        InMemoryWorkerQueue<WorkerTaskResult> resultQueue = new InMemoryWorkerQueue<>(100);
        WorkerTaskProcessor processor = newProcessor(resultQueue);
        // pre-kill so the callable reports KILLED; the asset block still runs on the way out
        processor.kill();

        processor.process(assetOutputMissingIdWorkerTask(AssetFailureBehavior.WARN));

        List<WorkerTaskResult> results = drain(resultQueue);
        assertThat(results.getLast().getTaskRun().getState().getCurrent()).isEqualTo(State.Type.KILLED);
    }

    // validate() re-checks the whole task bean, so an unrelated invalid field throws from the asset
    // render too. That is not a malformed declaration and must keep going through assetFailureBehavior.
    @Test
    void shouldNotReportAnUnrelatedTaskViolationAsAnInvalidAssetDeclaration() throws Exception {
        InMemoryWorkerQueue<WorkerTaskResult> resultQueue = new InMemoryWorkerQueue<>(100);
        WorkerTaskProcessor processor = newProcessor(resultQueue);

        processor.process(unrelatedViolationWithValidAssetsWorkerTask());

        List<WorkerTaskResult> results = drain(resultQueue);
        assertThat(results.getLast().getTaskRun().getState().getCurrent()).isEqualTo(State.Type.WARNING);
    }

    // The tests above populate Property.value eagerly, so the violation is visible to the first
    // whole-bean validate. A flow's YAML leaves it null until rendered, so the violation only surfaces
    // mid-attempt, after the task body succeeded — the path a user actually hits.
    @Test
    void shouldFailTaskWhenAssetDeclarationIsOnlyInvalidOnceRendered() throws Exception {
        InMemoryWorkerQueue<WorkerTaskResult> resultQueue = new InMemoryWorkerQueue<>(100);
        WorkerTaskProcessor processor = newProcessor(resultQueue);
        List<LogEntry> logs = new CopyOnWriteArrayList<>();
        logQueue.addListener(logs::add);

        processor.process(assetInputMissingIdFromExpressionWorkerTask(AssetFailureBehavior.IGNORE));

        List<WorkerTaskResult> results = drain(resultQueue);
        String taskRunId = results.getLast().getTaskRun().getId();
        assertThat(results.getLast().getTaskRun().getState().getCurrent()).isEqualTo(State.Type.FAILED);
        assertThat(results.getLast().getTaskRun().getAssetEmits()).isNullOrEmpty();
        LogEntry errorLog = TestsUtils.awaitLog(logs, log -> taskRunId.equals(log.getTaskRunId()) && log.getMessage() != null && log.getMessage().startsWith("Invalid asset declaration"));
        assertThat(errorLog).isNotNull();
        assertThat(errorLog.getMessage()).contains("assets.inputs[0].id").contains("must not be blank");
    }

    @Test
    void shouldFailTaskWhenAssetOutputDeclarationIsOnlyInvalidOnceRendered() throws Exception {
        InMemoryWorkerQueue<WorkerTaskResult> resultQueue = new InMemoryWorkerQueue<>(100);
        WorkerTaskProcessor processor = newProcessor(resultQueue);
        List<LogEntry> logs = new CopyOnWriteArrayList<>();
        logQueue.addListener(logs::add);

        processor.process(assetOutputMissingIdFromExpressionWorkerTask(AssetFailureBehavior.IGNORE));

        List<WorkerTaskResult> results = drain(resultQueue);
        String taskRunId = results.getLast().getTaskRun().getId();
        assertThat(results.getLast().getTaskRun().getState().getCurrent()).isEqualTo(State.Type.FAILED);
        assertThat(results.getLast().getTaskRun().getAssetEmits()).isNullOrEmpty();
        LogEntry errorLog = TestsUtils.awaitLog(logs, log -> taskRunId.equals(log.getTaskRunId()) && log.getMessage() != null && log.getMessage().startsWith("Invalid asset declaration"));
        assertThat(errorLog).isNotNull();
        assertThat(errorLog.getMessage()).contains("assets.outputs[0].id").contains("must not be blank");
    }

    private WorkerTaskProcessor newProcessor(WorkerQueue<WorkerTaskResult> resultQueue) {
        return new WorkerTaskProcessor(
            "test-worker",
            WorkerGroups.DEFAULT_ID,
            serverConfig,
            metricRegistry,
            workerSecurityService,
            tracerFactory.getTracer(Worker.class, "WORKER"),
            runContextInitializer,
            runContextLoggerFactory,
            resultQueue,
            new InMemoryWorkerQueue<>(100),
            executionKilledManager
        );
    }

    private WorkerTask failingWorkerTask() {
        AlwaysFail task = AlwaysFail.builder()
            .type(AlwaysFail.class.getName())
            .id("fail-task")
            .build();

        Flow flow = Flow.builder()
            .id(IdUtils.create())
            .namespace("org.flow.unit-test")
            .tasks(List.of(task))
            .build();

        Execution execution = TestsUtils.mockExecution(flow, Map.of());
        ResolvedTask resolvedTask = ResolvedTask.of(task);

        return WorkerTask.builder()
            .data(WorkerTaskData.from(runContextFactory.of(Map.of("key", "value"))))
            .task(task)
            .taskRun(TaskRun.of(execution, resolvedTask))
            .build();
    }

    private WorkerTask unrenderableOutputsWorkerTask() {
        AssetEmissionFailure task = AssetEmissionFailure.builder()
            .type(AssetEmissionFailure.class.getName())
            .id("asset-task")
            .assets(
                new AssetsDeclaration(
                    Property.ofValue(false),
                    Property.ofValue(List.of(new AssetIdentifier(null, "org.flow.unit-test", "declared-input", "MY_OWN_ASSET_TYPE"))),
                    // rendered value is a plain string, not JSON, so binding it as List<Asset> fails
                    Property.ofExpression("{{ 'not-json' }}"),
                    Property.ofValue(AssetFailureBehavior.WARN)
                )
            )
            .build();

        return workerTaskFor(task);
    }

    private WorkerTask namespacelessAssetsWorkerTask() {
        AssetEmissionFailure task = AssetEmissionFailure.builder()
            .type(AssetEmissionFailure.class.getName())
            .id("asset-task")
            .assets(
                new AssetsDeclaration(
                    Property.ofValue(false),
                    Property.ofValue(List.of(new AssetIdentifier(null, null, "declared-input", "MY_OWN_ASSET_TYPE"))),
                    Property.ofValue(List.<Asset> of(External.builder().id("declared-output").build())),
                    Property.ofValue(AssetFailureBehavior.WARN)
                )
            )
            .build();

        return workerTaskFor(task);
    }

    private WorkerTask assetEmissionFailureWorkerTask(AssetFailureBehavior assetFailureBehavior) {
        return assetEmissionFailureWorkerTask(assetFailureBehavior, false, false);
    }

    private WorkerTask assetEmissionFailureWorkerTask(AssetFailureBehavior assetFailureBehavior, boolean allowFailure, boolean allowWarning) {
        AssetEmissionFailure task = AssetEmissionFailure.builder()
            .type(AssetEmissionFailure.class.getName())
            .id("asset-task")
            .allowFailure(allowFailure)
            .allowWarning(allowWarning)
            // rendered value is a plain string, not JSON, so binding it as List<AssetIdentifier> fails
            .assets(new AssetsDeclaration(Property.ofValue(false), Property.ofExpression("{{ 'not-json' }}"), Property.ofValue(List.of()), Property.ofValue(assetFailureBehavior)))
            .build();

        return workerTaskFor(task);
    }

    private WorkerTask assetOutputMissingIdWorkerTask(AssetFailureBehavior assetFailureBehavior) {
        AssetEmissionFailure task = AssetEmissionFailure.builder()
            .type(AssetEmissionFailure.class.getName())
            .id("asset-output-missing-id-task")
            .assets(
                new AssetsDeclaration(
                    Property.ofValue(false),
                    Property.ofValue(List.of()),
                    Property.ofValue(List.of(Custom.builder().namespace("org.flow.tests").type("custom").build())),
                    Property.ofValue(assetFailureBehavior)
                )
            )
            .build();

        return workerTaskFor(task);
    }

    private WorkerTask assetInputMissingIdFromExpressionWorkerTask(AssetFailureBehavior assetFailureBehavior) {
        AssetEmissionFailure task = AssetEmissionFailure.builder()
            .type(AssetEmissionFailure.class.getName())
            .id("asset-input-missing-id-expression-task")
            .assets(
                new AssetsDeclaration(
                    Property.ofValue(false),
                    Property.ofExpression("{{ '[{\"namespace\":\"org.flow.tests\",\"type\":\"custom\"}]' }}"),
                    Property.ofValue(List.of()),
                    Property.ofValue(assetFailureBehavior)
                )
            )
            .build();

        return workerTaskFor(task);
    }

    private WorkerTask assetOutputMissingIdFromExpressionWorkerTask(AssetFailureBehavior assetFailureBehavior) {
        AssetEmissionFailure task = AssetEmissionFailure.builder()
            .type(AssetEmissionFailure.class.getName())
            .id("asset-output-missing-id-expression-task")
            .assets(
                new AssetsDeclaration(
                    Property.ofValue(false),
                    Property.ofValue(List.of()),
                    Property.ofExpression("{{ '[{\"type\":\"org.flow.core.models.assets.Custom\",\"namespace\":\"org.flow.tests\"}]' }}"),
                    Property.ofValue(assetFailureBehavior)
                )
            )
            .build();

        return workerTaskFor(task);
    }

    private WorkerTask assetInputMissingIdWorkerTask(AssetFailureBehavior assetFailureBehavior) {
        AssetEmissionFailure task = AssetEmissionFailure.builder()
            .type(AssetEmissionFailure.class.getName())
            .id("asset-input-missing-id-task")
            .assets(
                new AssetsDeclaration(
                    Property.ofValue(false),
                    Property.ofValue(List.of(new AssetIdentifier(null, "org.flow.tests", null, "custom"))),
                    Property.ofValue(List.of()),
                    Property.ofValue(assetFailureBehavior)
                )
            )
            .build();

        return workerTaskFor(task);
    }

    private WorkerTask unrelatedViolationWithValidAssetsWorkerTask() {
        UnrelatedViolation task = UnrelatedViolation.builder()
            .type(UnrelatedViolation.class.getName())
            .id("unrelated-violation-task")
            .count(Property.ofExpression("{{ 5 }}"))
            .assets(
                new AssetsDeclaration(
                    Property.ofValue(false),
                    Property.ofValue(List.of()),
                    Property.ofValue(List.of()),
                    Property.ofValue(AssetFailureBehavior.WARN)
                )
            )
            .build();

        return workerTaskFor(task);
    }

    private WorkerTask taskFailsAndAssetEmissionFailsWorkerTask(AssetFailureBehavior assetFailureBehavior) {
        return taskFailsAndAssetEmissionFailsWorkerTask(assetFailureBehavior, false);
    }

    private WorkerTask taskFailsAndAssetEmissionFailsWorkerTask(AssetFailureBehavior assetFailureBehavior, boolean allowFailure) {
        AlwaysFail task = AlwaysFail.builder()
            .type(AlwaysFail.class.getName())
            .id("failing-asset-task")
            .allowFailure(allowFailure)
            // rendered value is a plain string, not JSON, so binding it as List<AssetIdentifier> fails
            .assets(new AssetsDeclaration(Property.ofValue(false), Property.ofExpression("{{ 'not-json' }}"), Property.ofValue(List.of()), Property.ofValue(assetFailureBehavior)))
            .build();

        return workerTaskFor(task);
    }

    private WorkerTask workerTaskFor(Task task) {
        Flow flow = Flow.builder()
            .id(IdUtils.create())
            .namespace("org.flow.unit-test")
            .tasks(List.of(task))
            .build();

        Execution execution = TestsUtils.mockExecution(flow, Map.of());
        ResolvedTask resolvedTask = ResolvedTask.of(task);

        return WorkerTask.builder()
            .data(WorkerTaskData.from(runContextFactory.of(Map.of("key", "value"))))
            .task(task)
            .taskRun(TaskRun.of(execution, resolvedTask))
            .build();
    }

    private static List<WorkerTaskResult> drain(WorkerQueue<WorkerTaskResult> queue) throws InterruptedException {
        List<WorkerTaskResult> results = new ArrayList<>();
        WorkerTaskResult result;
        while ((result = queue.poll(Duration.ZERO)) != null) {
            results.add(result);
        }
        return results;
    }

    /**
     * A task that always fails on its own (throws when run), modeling e.g. a script container exiting
     * non-zero. Constructed and executed directly by the processor, so no plugin registration is needed.
     */
    @SuperBuilder
    @Getter
    @NoArgsConstructor
    public static class AlwaysFail extends Task implements RunnableTask<VoidOutput> {
        @Override
        public VoidOutput run(RunContext runContext) {
            throw new RuntimeException("simulated task failure during shutdown drain");
        }
    }

    /**
     * A task that succeeds on its own, so only its asset declaration decides the outcome. Constructed and
     * executed directly by the processor, so no plugin registration is needed.
     */
    @SuperBuilder
    @Getter
    @NoArgsConstructor
    public static class AssetEmissionFailure extends Task implements RunnableTask<VoidOutput> {
        @Override
        public VoidOutput run(RunContext runContext) {
            // null, not new VoidOutput(): the empty bean has no properties and Jackson's
            // FAIL_ON_EMPTY_BEANS would blow up when the processor serializes the output to a map
            return null;
        }
    }

    /**
     * A task that succeeds but leaves an invalid value on an unrelated property, by rendering it without
     * going through the validating {@code RunContextProperty}. The asset render's whole-bean validate
     * then reports that violation, which is not a malformed asset declaration.
     */
    @SuperBuilder
    @Getter
    @NoArgsConstructor
    public static class UnrelatedViolation extends Task implements RunnableTask<VoidOutput> {
        private Property<@Min(10) Integer> count;

        @Override
        public VoidOutput run(RunContext runContext) throws Exception {
            Property.as(this.count, runContext, Integer.class);
            return null;
        }
    }
}
