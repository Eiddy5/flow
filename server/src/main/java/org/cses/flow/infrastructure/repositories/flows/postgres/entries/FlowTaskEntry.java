package org.cses.flow.infrastructure.repositories.flows.postgres.entries;

import org.cses.flow.core.domains.tasks.RouteExpression;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.domains.tasks.TaskTypeDispatcher;
import org.flow.gen.flow.pojos.FlowTasksObject;
import org.flow.gen.flow.records.FlowTasksRecord;
import org.paas.json.JsonObject;
import org.paas.json.JsonObjects;

import java.util.List;
import static org.flow.gen.flow.Tables.FLOW_TASKS;

public final class FlowTaskEntry extends FlowTasksObject {

    public static FlowTaskEntry fromRecord(FlowTasksRecord record) {
        FlowTaskEntry entry = new FlowTaskEntry();
        entry.companyId = record.getCompanyId();
        entry.id = record.getId();
        entry.type = record.getType();
        entry.route = record.getRoute();
        entry.inputs = JsonObjects.Parse(
            record.get(FLOW_TASKS.INPUTS).data()
        );
        entry.outputs = JsonObjects.Parse(
            record.get(FLOW_TASKS.OUTPUTS).data()
        );
        entry.properties = JsonObject.Parse(
            record.get(FLOW_TASKS.PROPERTIES).data()
        );
        entry.flowId = record.getFlowId();
        entry.flowReversion = record.getFlowReversion();
        entry.parentId = record.getParentId();
        entry.order = record.getOrder();
        entry.key = record.getKey();
        entry.dependOn = JsonObjects.Parse(
            record.get(FLOW_TASKS.DEPEND_ON).data()
        );
        return entry;
    }

    public static FlowTaskEntry fromDomain(
        String companyId,
        String flowId,
        long flowReversion,
        Task task,
        int order,
        TaskTypeDispatcher dispatcher
    ) {
        FlowTaskEntry entry = new FlowTaskEntry();
        entry.companyId = companyId;
        entry.id = task.id();
        entry.type = task.type();
        entry.route = task.route().source();
        entry.inputs = DataJsonCodec.encode(task.inputs());
        entry.outputs = DataJsonCodec.encode(task.outputs());
        entry.properties = JsonObject.FromMap(
            dispatcher.properties(task)
        );
        entry.flowId = flowId;
        entry.flowReversion = flowReversion;
        entry.parentId = task.parentId().orElse(null);
        entry.order = order;
        entry.key = task.key();
        entry.dependOn = JsonObjects.FromList(task.dependOn());
        return entry;
    }

    public Task toDomain(
        TaskTypeDispatcher dispatcher,
        List<Task> children
    ) {
        return dispatcher.restore(
            id,
            parentId,
            key,
            type,
            DataJsonCodec.decodeInputs(inputs, "Task.inputs"),
            DataJsonCodec.decodeOutputs(outputs, "Task.outputs"),
            RouteExpression.parse(route),
            dependOn == null ? List.of() : dependOn.asStrings(),
            properties == null ? java.util.Map.of() : properties.asMap(),
            children
        );
    }
}
