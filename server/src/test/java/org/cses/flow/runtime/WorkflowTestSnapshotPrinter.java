package org.cses.flow.runtime;

import java.util.List;
import org.cses.flow.definition.model.Edge;
import org.cses.flow.definition.model.Flow;
import org.cses.flow.definition.model.Node;
import org.cses.flow.runtime.context.CommandContext;
import org.cses.flow.runtime.model.Activity;
import org.cses.flow.runtime.model.Executor;
import org.cses.flow.runtime.model.Process;
import org.cses.flow.runtime.model.Task;

final class WorkflowTestSnapshotPrinter {

    String render(
            String scenario,
            String stage,
            Flow flow,
            Process process,
            List<Activity> activities,
            List<Task> tasks,
            CommandContext commandContext) {
        StringBuilder output = new StringBuilder();
        output.append(System.lineSeparator())
                .append("========== WORKFLOW SNAPSHOT | ")
                .append(scenario)
                .append(" | ")
                .append(stage)
                .append(" ==========")
                .append(System.lineSeparator());

        appendFlow(output, flow);
        appendProcess(output, process);
        appendExecutors(output, process.executors());
        appendActivities(output, activities);
        appendTasks(output, tasks);
        output.append("EXECUTION_QUEUE").append(System.lineSeparator())
                .append("  empty=").append(commandContext.executionQueue().isEmpty())
                .append(System.lineSeparator())
                .append("============================================================")
                .append(System.lineSeparator());
        return output.toString();
    }

    private void appendFlow(StringBuilder output, Flow flow) {
        output.append("FLOW").append(System.lineSeparator())
                .append("  id=").append(flow.id())
                .append(", key=").append(flow.key())
                .append(", name=").append(flow.name())
                .append(", version=").append(flow.version())
                .append(", state=").append(flow.state())
                .append(", createdAt=").append(flow.createdAt())
                .append(", deployedAt=").append(flow.deployedAt())
                .append(System.lineSeparator())
                .append("  nodes:").append(System.lineSeparator());
        for (Node node : flow.nodes()) {
            output.append("    - id=").append(node.id())
                    .append(", flowId=").append(node.flowId())
                    .append(", name=").append(node.name())
                    .append(", type=").append(node.type())
                    .append(", config=").append(node.config())
                    .append(", incoming=").append(edgeIds(node.incoming()))
                    .append(", outgoing=").append(edgeIds(node.outgoing()))
                    .append(System.lineSeparator());
        }
        output.append("  edges:").append(System.lineSeparator());
        for (Edge edge : flow.edges()) {
            output.append("    - id=").append(edge.id())
                    .append(", flowId=").append(edge.flowId())
                    .append(", sourceId=").append(edge.sourceId())
                    .append(", targetId=").append(edge.targetId())
                    .append(System.lineSeparator());
        }
    }

    private void appendProcess(StringBuilder output, Process process) {
        output.append("PROCESS").append(System.lineSeparator())
                .append("  id=").append(process.id())
                .append(", flowId=").append(process.flowId())
                .append(", flowKey=").append(process.flowKey())
                .append(", flowVersion=").append(process.flowVersion())
                .append(", state=").append(process.state())
                .append(", variables=").append(process.variables())
                .append(", startedAt=").append(process.startedAt())
                .append(", endedAt=").append(process.endedAt())
                .append(System.lineSeparator());
    }

    private void appendExecutors(StringBuilder output, List<Executor> executors) {
        output.append("EXECUTORS").append(System.lineSeparator());
        if (executors.isEmpty()) {
            output.append("  (empty)").append(System.lineSeparator());
            return;
        }
        for (Executor executor : executors) {
            output.append("  - id=").append(executor.id())
                    .append(", processId=").append(executor.processId())
                    .append(", parentId=").append(executor.parentId())
                    .append(", currentNodeId=").append(executor.currentNodeId())
                    .append(", state=").append(executor.state())
                    .append(", createdAt=").append(executor.createdAt())
                    .append(", updatedAt=").append(executor.updatedAt())
                    .append(System.lineSeparator());
        }
    }

    private void appendActivities(StringBuilder output, List<Activity> activities) {
        output.append("ACTIVITIES").append(System.lineSeparator());
        if (activities.isEmpty()) {
            output.append("  (empty)").append(System.lineSeparator());
            return;
        }
        for (Activity activity : activities) {
            output.append("  - id=").append(activity.id())
                    .append(", processId=").append(activity.processId())
                    .append(", executorId=").append(activity.executorId())
                    .append(", nodeId=").append(activity.nodeId())
                    .append(", nodeType=").append(activity.nodeType())
                    .append(", state=").append(activity.state())
                    .append(", outputVariables=").append(activity.outputVariables())
                    .append(", startedAt=").append(activity.startedAt())
                    .append(", endedAt=").append(activity.endedAt())
                    .append(System.lineSeparator());
        }
    }

    private void appendTasks(StringBuilder output, List<Task> tasks) {
        output.append("TASKS").append(System.lineSeparator());
        if (tasks.isEmpty()) {
            output.append("  (empty)").append(System.lineSeparator());
            return;
        }
        for (Task task : tasks) {
            output.append("  - id=").append(task.id())
                    .append(", processId=").append(task.processId())
                    .append(", executorId=").append(task.executorId())
                    .append(", activityId=").append(task.activityId())
                    .append(", nodeId=").append(task.nodeId())
                    .append(", name=").append(task.name())
                    .append(", type=").append(task.type())
                    .append(", state=").append(task.state())
                    .append(", result=").append(task.result())
                    .append(", completedBy=").append(task.completedBy())
                    .append(", idempotencyKey=").append(task.idempotencyKey())
                    .append(", createdAt=").append(task.createdAt())
                    .append(", completedAt=").append(task.completedAt())
                    .append(System.lineSeparator());
        }
    }

    private List<String> edgeIds(List<Edge> edges) {
        return edges.stream().map(Edge::id).toList();
    }
}
