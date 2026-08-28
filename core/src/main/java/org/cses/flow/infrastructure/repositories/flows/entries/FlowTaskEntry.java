package org.cses.flow.infrastructure.repositories.flows.entries;

import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.infrastructure.repositories.flows.codec.DataJsonCodec;
import org.cses.flow.infrastructure.repositories.flows.codec.TaskPropertiesCodec;
import org.flow.gen.flow.pojos.FlowTasksObject;
import org.paas.json.JsonObjects;

import java.util.List;

public class FlowTaskEntry extends FlowTasksObject {

    public static FlowTaskEntry from(
        String companyId,
        String flowKey,
        long flowVersion,
        Task task,
        String parentId,
        int order
    ) {
        FlowTaskEntry entry = new FlowTaskEntry();
        entry.companyId = companyId;
        entry.id = task.id();
        entry.type = task.getType();
        entry.route = "DIRECT";
        entry.inputs = DataJsonCodec.encode(task.inputs());
        entry.outputs = DataJsonCodec.encode(task.outputs());
        entry.properties = TaskPropertiesCodec.encode(task);
        entry.flowKey = flowKey;
        entry.flowVersion = flowVersion;
        entry.parentId = parentId;
        entry.order = order;
        entry.key = task.key();
        entry.dependOn = JsonObjects.Create();
        return entry;
    }

    public Task to(List<Task> children) {
        return TaskPropertiesCodec.decode(
            properties,
            id,
            type,
            key,
            DataJsonCodec.decodeInputs(inputs, "Task.inputs"),
            DataJsonCodec.decodeOutputs(outputs, "Task.outputs"),
            children
        );
    }
}
