package org.cses.flow.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.cses.flow.runtime.behavior.ActivityBehaviorRegistry;
import org.cses.flow.runtime.behavior.impl.ActionActivityBehavior;
import org.cses.flow.runtime.behavior.impl.EndActivityBehavior;
import org.cses.flow.runtime.behavior.impl.StartActivityBehavior;
import org.cses.flow.runtime.behavior.impl.WaitActivityBehavior;
import org.cses.flow.definition.command.CreateFlowCommand;
import org.cses.flow.definition.model.Edge;
import org.cses.flow.definition.model.Flow;
import org.cses.flow.definition.model.Node;
import org.cses.flow.definition.model.NodeType;
import org.cses.flow.definition.service.FlowService;
import org.cses.flow.definition.service.FlowValidator;
import org.cses.flow.infrastructure.memory.InMemoryEngineSessionFactory;
import org.cses.flow.infrastructure.memory.InMemoryFlowRepository;
import org.cses.flow.infrastructure.memory.InMemoryIdGenerator;
import org.cses.flow.infrastructure.memory.InMemoryRuntimeQuery;
import org.cses.flow.infrastructure.memory.InMemoryRuntimeState;
import org.cses.flow.runtime.context.CommandContext;
import org.cses.flow.runtime.context.CommandContextFactory;
import org.cses.flow.runtime.engine.FlowEngine;
import org.cses.flow.runtime.engine.EngineConfiguration;
import org.cses.flow.runtime.execution.CommandExecutor;
import org.cses.flow.runtime.execution.ExecutionRunner;
import org.cses.flow.runtime.model.Activity;
import org.cses.flow.runtime.model.ActivityState;
import org.cses.flow.runtime.model.ExecutorState;
import org.cses.flow.runtime.model.Process;
import org.cses.flow.runtime.model.ProcessState;
import org.cses.flow.shared.IdGenerator;
import org.cses.flow.runtime.model.Task;
import org.cses.flow.runtime.model.TaskState;
import org.cses.flow.runtime.model.TaskType;
import org.cses.flow.task.command.CompleteTaskRequest;
import org.cses.flow.task.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FlowStartAndNodeProgressionTest {

    private Fixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new Fixture();
    }

    @Test
    void shouldCompleteFlowContainingOnlyAutomaticNodes() {
        Flow versionOne = fixture.deploy(
                "automatic-flow",
                fixture.start("start"),
                fixture.action("action-a"),
                fixture.action("action-b"),
                fixture.end("end"));
        Flow versionTwo = fixture.deploy(
                "automatic-flow",
                fixture.start("start"),
                fixture.action("action-a"),
                fixture.action("action-b"),
                fixture.end("end"));

        Process process = fixture.flowEngine.start(versionOne.id());
        String snapshot = fixture.printSnapshot("S1", "start 返回后", process);

        assertTrue(snapshot.contains("FLOW"));
        assertTrue(snapshot.contains("PROCESS"));
        assertTrue(snapshot.contains("EXECUTORS"));
        assertTrue(snapshot.contains("ACTIVITIES"));
        assertTrue(snapshot.contains("TASKS"));
        assertTrue(snapshot.contains("EXECUTION_QUEUE"));
        assertTrue(snapshot.contains("version=" + versionTwo.version()));
        assertTrue(snapshot.contains("state=COMPLETED"));
        assertEquals(versionTwo.id(), process.flowId());
        assertEquals(versionTwo.version(), process.flowVersion());
        assertEquals(ProcessState.COMPLETED, process.state());
        assertEquals(ExecutorState.COMPLETED, process.rootExecutor().state());
        assertEquals("end", process.rootExecutor().currentNodeId());
        assertEquals(
                List.of("start", "action-a", "action-b", "end"),
                fixture.activityNodeIds(process.id()));
        assertTrue(fixture.activities(process.id()).stream()
                .allMatch(activity -> activity.state() == ActivityState.COMPLETED));
        assertTrue(fixture.runtimeQuery.findTasksByProcessId(process.id()).isEmpty());
        assertTrue(fixture.contextFactory.lastContext().executionQueue().isEmpty());
    }

    @Test
    void shouldWaitForExternalCompleteAtManualNode() {
        Flow flow = fixture.deploy(
                "manual-flow",
                fixture.start("start"),
                fixture.waitNode("wait-a"),
                fixture.end("end"));

        Process process = fixture.flowEngine.start(flow.id());
        Task task = fixture.onlyTask(process.id());
        fixture.printSnapshot("S2", "start 返回后", process);

        assertEquals(ProcessState.RUNNING, process.state());
        assertEquals(ExecutorState.WAITING, process.rootExecutor().state());
        assertEquals("wait-a", process.rootExecutor().currentNodeId());
        assertEquals(List.of("start", "wait-a"), fixture.activityNodeIds(process.id()));
        assertEquals(ActivityState.COMPLETED, fixture.activity(process.id(), "start").state());
        assertEquals(ActivityState.RUNNING, fixture.activity(process.id(), "wait-a").state());
        assertEquals(TaskState.CREATED, task.state());
        assertEquals(TaskType.MANUAL, task.type());
        assertEquals(process.id(), task.processId());
        assertEquals(process.rootExecutor().id(), task.executorId());
        assertEquals(fixture.activity(process.id(), "wait-a").id(), task.activityId());
        assertEquals("wait-a", task.nodeId());
        assertTrue(fixture.contextFactory.lastContext().executionQueue().isEmpty());

        process = fixture.taskService.complete(new CompleteTaskRequest(
                task.id(), Map.of("approved", true), "operator-a", "complete-wait-a"));
        task = fixture.task(task.id());
        fixture.printSnapshot("S2", "Task complete 返回后", process);

        assertEquals(TaskState.COMPLETED, task.state());
        assertEquals(ActivityState.COMPLETED, fixture.activity(process.id(), "wait-a").state());
        assertEquals(
                List.of("start", "wait-a", "end"),
                fixture.activityNodeIds(process.id()));
        assertEquals(ActivityState.COMPLETED, fixture.activity(process.id(), "end").state());
        assertEquals(ProcessState.COMPLETED, process.state());
        assertEquals(ExecutorState.COMPLETED, process.rootExecutor().state());
        assertEquals("end", process.rootExecutor().currentNodeId());
        assertTrue(fixture.contextFactory.lastContext().executionQueue().isEmpty());
    }

    @Test
    void shouldContinueAutomaticNodesAfterManualTaskCompleted() {
        Flow flow = fixture.deploy(
                "mixed-flow",
                fixture.start("start"),
                fixture.action("action-a"),
                fixture.waitNode("wait-a"),
                fixture.action("action-b"),
                fixture.end("end"));

        Process process = fixture.flowEngine.start(flow.id());
        fixture.printSnapshot("S3", "start 返回后", process);

        assertEquals(
                List.of("start", "action-a", "wait-a"),
                fixture.activityNodeIds(process.id()));
        assertEquals(ActivityState.COMPLETED, fixture.activity(process.id(), "start").state());
        assertEquals(ActivityState.COMPLETED, fixture.activity(process.id(), "action-a").state());
        assertEquals(ActivityState.RUNNING, fixture.activity(process.id(), "wait-a").state());
        assertEquals(ExecutorState.WAITING, process.rootExecutor().state());
        assertTrue(fixture.activities(process.id()).stream()
                .noneMatch(activity -> activity.nodeId().equals("action-b")));

        Task task = fixture.onlyTask(process.id());
        assertEquals(TaskState.CREATED, task.state());
        assertEquals(TaskType.MANUAL, task.type());
        process = fixture.taskService.complete(new CompleteTaskRequest(
                task.id(), Map.of("approved", true), "operator-a", "complete-mixed"));
        task = fixture.task(task.id());
        fixture.printSnapshot("S3", "Task complete 返回后", process);

        assertEquals(TaskState.COMPLETED, task.state());
        assertEquals(ActivityState.COMPLETED, fixture.activity(process.id(), "wait-a").state());
        assertEquals(
                List.of("start", "action-a", "wait-a", "action-b", "end"),
                fixture.activityNodeIds(process.id()));
        assertEquals(1, fixture.activityCount(process.id(), "action-a"));
        assertEquals(1, fixture.activityCount(process.id(), "action-b"));
        assertEquals(ProcessState.COMPLETED, process.state());
        assertEquals(ExecutorState.COMPLETED, process.rootExecutor().state());
        assertTrue(fixture.contextFactory.lastContext().executionQueue().isEmpty());
    }

    @Test
    void shouldStopAtEachManualNodeInSequence() {
        Flow flow = fixture.deploy(
                "sequential-manual-flow",
                fixture.start("start"),
                fixture.waitNode("wait-a"),
                fixture.action("action-a"),
                fixture.waitNode("wait-b"),
                fixture.end("end"));

        Process process = fixture.flowEngine.start(flow.id());
        Task taskA = fixture.onlyCreatedTask(process.id());
        fixture.printSnapshot("S4", "start 返回后", process);

        assertEquals("wait-a", process.rootExecutor().currentNodeId());
        assertEquals(ExecutorState.WAITING, process.rootExecutor().state());
        assertEquals(1, fixture.runtimeQuery.findTasksByProcessId(process.id()).size());
        assertEquals("wait-a", taskA.nodeId());
        assertTrue(fixture.contextFactory.lastContext().executionQueue().isEmpty());

        process = fixture.taskService.complete(new CompleteTaskRequest(
                taskA.id(), Map.of("result", "a"), "operator-a", "complete-a"));
        taskA = fixture.task(taskA.id());

        Task taskB = fixture.onlyCreatedTask(process.id());
        fixture.printSnapshot("S4", "Task_A complete 返回后", process);
        assertNotEquals(taskA.id(), taskB.id());
        assertNotEquals(taskA.activityId(), taskB.activityId());
        assertNotEquals(taskA.nodeId(), taskB.nodeId());
        assertEquals("wait-b", taskB.nodeId());
        assertEquals(TaskState.COMPLETED, taskA.state());
        assertEquals(TaskState.CREATED, taskB.state());
        assertEquals(ActivityState.COMPLETED, fixture.activity(process.id(), "wait-a").state());
        assertEquals(ActivityState.COMPLETED, fixture.activity(process.id(), "action-a").state());
        assertEquals(ActivityState.RUNNING, fixture.activity(process.id(), "wait-b").state());
        assertEquals(1, fixture.activityCount(process.id(), "action-a"));
        assertEquals(ProcessState.RUNNING, process.state());
        assertEquals(ExecutorState.WAITING, process.rootExecutor().state());
        assertEquals("wait-b", process.rootExecutor().currentNodeId());
        assertEquals(
                List.of("start", "wait-a", "action-a", "wait-b"),
                fixture.activityNodeIds(process.id()));
        assertTrue(fixture.contextFactory.lastContext().executionQueue().isEmpty());

        process = fixture.taskService.complete(new CompleteTaskRequest(
                taskB.id(), Map.of("result", "b"), "operator-b", "complete-b"));
        taskB = fixture.task(taskB.id());
        fixture.printSnapshot("S4", "Task_B complete 返回后", process);

        assertEquals(TaskState.COMPLETED, taskB.state());
        assertEquals(ActivityState.COMPLETED, fixture.activity(process.id(), "wait-b").state());
        assertEquals(
                List.of("start", "wait-a", "action-a", "wait-b", "end"),
                fixture.activityNodeIds(process.id()));
        assertEquals(ProcessState.COMPLETED, process.state());
        assertEquals(ExecutorState.COMPLETED, process.rootExecutor().state());
        assertEquals("end", process.rootExecutor().currentNodeId());
        assertEquals(ActivityState.COMPLETED, fixture.activity(process.id(), "end").state());
        assertTrue(fixture.contextFactory.lastContext().executionQueue().isEmpty());
    }

    @Test
    void shouldTreatRepeatedCompletionWithTheSameIdempotencyKeyAsSuccess() {
        Flow flow = fixture.deploy(
                "idempotent-flow",
                fixture.start("start"),
                fixture.waitNode("wait-a"),
                fixture.end("end"));
        Process waiting = fixture.flowEngine.start(flow.id());
        Task task = fixture.onlyTask(waiting.id());
        CompleteTaskRequest request = new CompleteTaskRequest(
                task.id(), Map.of("approved", true), "operator-a", "same-key");

        Process completed = fixture.taskService.complete(request);
        Process retried = fixture.taskService.complete(request);

        assertEquals(ProcessState.COMPLETED, completed.state());
        assertEquals(ProcessState.COMPLETED, retried.state());
        assertEquals(3, fixture.activities(waiting.id()).size());
        assertEquals(1, fixture.runtimeQuery.findTasksByProcessId(waiting.id()).size());
        assertEquals(TaskState.COMPLETED, fixture.task(task.id()).state());
    }

    @Test
    void shouldRollbackAllRuntimeWritesWhenNodeExecutionFails() {
        Node invalidWait = new Node(
                "wait-a",
                "wait-a",
                NodeType.WAIT,
                Map.of("completion", Map.of("mode", "automatic")));
        Flow flow = fixture.deploy(
                "invalid-wait-flow",
                fixture.start("start"),
                invalidWait,
                fixture.end("end"));

        assertThrows(IllegalArgumentException.class, () -> fixture.flowEngine.start(flow.id()));

        assertEquals(0, fixture.runtimeState.processCount());
        assertEquals(0, fixture.runtimeState.activityCount());
        assertEquals(0, fixture.runtimeState.taskCount());
        assertEquals(0, fixture.runtimeState.committedTransactionCount());
    }

    private static final class Fixture {

        private final IdGenerator idGenerator = new InMemoryIdGenerator();
        private final InMemoryFlowRepository flowRepository = new InMemoryFlowRepository();
        private final InMemoryRuntimeState runtimeState = new InMemoryRuntimeState();
        private final InMemoryRuntimeQuery runtimeQuery = new InMemoryRuntimeQuery(runtimeState);
        private final WorkflowTestSnapshotPrinter snapshotPrinter = new WorkflowTestSnapshotPrinter();
        private final FlowService flowService = new FlowService(
                flowRepository, new FlowValidator(), idGenerator);
        private final ActivityBehaviorRegistry behaviorRegistry = new ActivityBehaviorRegistry(Map.of(
                NodeType.START, new StartActivityBehavior(),
                NodeType.ACTION, new ActionActivityBehavior(),
                NodeType.WAIT, new WaitActivityBehavior(),
                NodeType.END, new EndActivityBehavior()));
        private final EngineConfiguration configuration = new EngineConfiguration(
                idGenerator, behaviorRegistry);
        private final InMemoryEngineSessionFactory sessionFactory =
                new InMemoryEngineSessionFactory(flowRepository, runtimeState);
        private final CapturingCommandContextFactory contextFactory =
                new CapturingCommandContextFactory(sessionFactory, configuration);
        private final CommandExecutor commandExecutor = new CommandExecutor(
                contextFactory, new ExecutionRunner());
        private final FlowEngine flowEngine = new FlowEngine(commandExecutor);
        private final TaskService taskService = new TaskService(flowEngine);

        private Flow deploy(String key, Node... nodes) {
            List<Edge> edges = new ArrayList<>();
            for (int index = 0; index < nodes.length - 1; index++) {
                Node source = nodes[index];
                Node target = nodes[index + 1];
                edges.add(new Edge(
                        "edge-" + source.id() + "-" + target.id(),
                        source.id(),
                        target.id()));
            }

            Flow draft = flowService.create(new CreateFlowCommand(
                    key, key, List.of(nodes), edges));
            return flowService.deploy(draft.id());
        }

        private Node start(String id) {
            return new Node(id, id, NodeType.START, Map.of());
        }

        private Node action(String id) {
            return new Node(id, id, NodeType.ACTION, Map.of("executor", "noop"));
        }

        private Node waitNode(String id) {
            return new Node(
                    id,
                    id,
                    NodeType.WAIT,
                    Map.of(
                            "taskName", id,
                            "completion", Map.of("mode", "manual")));
        }

        private Node end(String id) {
            return new Node(id, id, NodeType.END, Map.of());
        }

        private List<Activity> activities(String processId) {
            return runtimeQuery.findActivitiesByProcessId(processId);
        }

        private List<String> activityNodeIds(String processId) {
            return activities(processId).stream().map(Activity::nodeId).toList();
        }

        private Activity activity(String processId, String nodeId) {
            return activities(processId).stream()
                    .filter(activity -> activity.nodeId().equals(nodeId))
                    .findFirst()
                    .orElseThrow();
        }

        private long activityCount(String processId, String nodeId) {
            return activities(processId).stream()
                    .filter(activity -> activity.nodeId().equals(nodeId))
                    .count();
        }

        private String printSnapshot(String scenario, String stage, Process process) {
            Flow flow = flowRepository.findById(process.flowId()).orElseThrow();
            String snapshot = snapshotPrinter.render(
                    scenario,
                    stage,
                    flow,
                    process,
                    runtimeQuery.findActivitiesByProcessId(process.id()),
                    runtimeQuery.findTasksByProcessId(process.id()),
                    contextFactory.lastContext());
            System.out.print(snapshot);
            return snapshot;
        }

        private Task onlyTask(String processId) {
            List<Task> tasks = runtimeQuery.findTasksByProcessId(processId);
            assertEquals(1, tasks.size());
            return tasks.getFirst();
        }

        private Task onlyCreatedTask(String processId) {
            List<Task> tasks = runtimeQuery.findTasksByProcessId(processId).stream()
                    .filter(task -> task.state() == TaskState.CREATED)
                    .toList();
            assertEquals(1, tasks.size());
            return tasks.getFirst();
        }

        private Task task(String taskId) {
            return runtimeQuery.findTaskById(taskId).orElseThrow();
        }
    }

    private static final class CapturingCommandContextFactory extends CommandContextFactory {

        private CommandContext lastContext;

        private CapturingCommandContextFactory(
                InMemoryEngineSessionFactory sessionFactory,
                EngineConfiguration configuration) {
            super(sessionFactory, configuration);
        }

        @Override
        public CommandContext open() {
            lastContext = super.open();
            return lastContext;
        }

        private CommandContext lastContext() {
            return lastContext;
        }
    }
}
