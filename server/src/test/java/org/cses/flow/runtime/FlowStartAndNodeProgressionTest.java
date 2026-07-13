package org.cses.flow.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.cses.flow.behavior.ActivityBehaviorRegistry;
import org.cses.flow.behavior.action.ActionActivityBehavior;
import org.cses.flow.behavior.end.EndActivityBehavior;
import org.cses.flow.behavior.start.StartActivityBehavior;
import org.cses.flow.behavior.wait.WaitActivityBehavior;
import org.cses.flow.definition.command.CreateFlowCommand;
import org.cses.flow.definition.model.Edge;
import org.cses.flow.definition.model.Flow;
import org.cses.flow.definition.model.Node;
import org.cses.flow.definition.model.NodeType;
import org.cses.flow.definition.service.FlowService;
import org.cses.flow.definition.service.FlowValidator;
import org.cses.flow.infrastructure.memory.InMemoryActivityRepository;
import org.cses.flow.infrastructure.memory.InMemoryFlowRepository;
import org.cses.flow.infrastructure.memory.InMemoryIdGenerator;
import org.cses.flow.infrastructure.memory.InMemoryProcessRepository;
import org.cses.flow.infrastructure.memory.InMemoryTaskRepository;
import org.cses.flow.runtime.command.CommandExecutor;
import org.cses.flow.runtime.context.FlowContext;
import org.cses.flow.runtime.context.FlowContextFactory;
import org.cses.flow.runtime.engine.FlowEngine;
import org.cses.flow.runtime.execution.ExecutionOperationFactory;
import org.cses.flow.runtime.execution.ExecutionRunner;
import org.cses.flow.runtime.model.Activity;
import org.cses.flow.runtime.model.ActivityState;
import org.cses.flow.runtime.model.ExecutorState;
import org.cses.flow.runtime.model.Process;
import org.cses.flow.runtime.model.ProcessState;
import org.cses.flow.shared.IdGenerator;
import org.cses.flow.task.command.CompleteTaskCommand;
import org.cses.flow.task.model.Task;
import org.cses.flow.task.model.TaskState;
import org.cses.flow.task.model.TaskType;
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
        assertTrue(fixture.taskRepository.findByProcessId(process.id()).isEmpty());
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

        fixture.taskService.complete(new CompleteTaskCommand(
                task.id(), Map.of("approved", true), "operator-a", "complete-wait-a"));

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
        fixture.taskService.complete(new CompleteTaskCommand(
                task.id(), Map.of("approved", true), "operator-a", "complete-mixed"));

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

        assertEquals("wait-a", process.rootExecutor().currentNodeId());
        assertEquals(ExecutorState.WAITING, process.rootExecutor().state());
        assertEquals(1, fixture.taskRepository.findByProcessId(process.id()).size());
        assertEquals("wait-a", taskA.nodeId());
        assertTrue(fixture.contextFactory.lastContext().executionQueue().isEmpty());

        fixture.taskService.complete(new CompleteTaskCommand(
                taskA.id(), Map.of("result", "a"), "operator-a", "complete-a"));

        Task taskB = fixture.onlyCreatedTask(process.id());
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

        fixture.taskService.complete(new CompleteTaskCommand(
                taskB.id(), Map.of("result", "b"), "operator-b", "complete-b"));

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

    private static final class Fixture {

        private final IdGenerator idGenerator = new InMemoryIdGenerator();
        private final InMemoryFlowRepository flowRepository = new InMemoryFlowRepository();
        private final InMemoryProcessRepository processRepository = new InMemoryProcessRepository();
        private final InMemoryActivityRepository activityRepository = new InMemoryActivityRepository();
        private final InMemoryTaskRepository taskRepository = new InMemoryTaskRepository();
        private final FlowService flowService = new FlowService(
                flowRepository, new FlowValidator(), idGenerator);
        private final ActivityBehaviorRegistry behaviorRegistry = new ActivityBehaviorRegistry(Map.of(
                NodeType.START, new StartActivityBehavior(),
                NodeType.ACTION, new ActionActivityBehavior(),
                NodeType.WAIT, new WaitActivityBehavior(),
                NodeType.END, new EndActivityBehavior()));
        private final ExecutionOperationFactory operationFactory = new ExecutionOperationFactory(
                idGenerator,
                processRepository,
                activityRepository,
                taskRepository,
                behaviorRegistry);
        private final CapturingFlowContextFactory contextFactory = new CapturingFlowContextFactory();
        private final CommandExecutor commandExecutor = new CommandExecutor(new ExecutionRunner());
        private final FlowEngine flowEngine = new FlowEngine(
                flowRepository,
                processRepository,
                activityRepository,
                taskRepository,
                idGenerator,
                contextFactory,
                commandExecutor,
                operationFactory,
                behaviorRegistry);
        private final TaskService taskService = new TaskService(taskRepository, flowEngine);

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
            return activityRepository.findByProcessId(processId);
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

        private Task onlyTask(String processId) {
            List<Task> tasks = taskRepository.findByProcessId(processId);
            assertEquals(1, tasks.size());
            return tasks.getFirst();
        }

        private Task onlyCreatedTask(String processId) {
            List<Task> tasks = taskRepository.findByProcessId(processId).stream()
                    .filter(task -> task.state() == TaskState.CREATED)
                    .toList();
            assertEquals(1, tasks.size());
            return tasks.getFirst();
        }
    }

    private static final class CapturingFlowContextFactory extends FlowContextFactory {

        private FlowContext lastContext;

        @Override
        public FlowContext create(Flow flow) {
            lastContext = super.create(flow);
            return lastContext;
        }

        @Override
        public FlowContext create(Flow flow, Process process) {
            lastContext = super.create(flow, process);
            return lastContext;
        }

        private FlowContext lastContext() {
            return lastContext;
        }
    }
}
