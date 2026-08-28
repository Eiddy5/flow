package org.cses.flow.infrastructure.repositories.flows.entries;

import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.infrastructure.repositories.flows.codec.DataJsonCodec;
import org.cses.flow.infrastructure.repositories.flows.codec.TaskPropertiesCodec;
import org.flow.gen.flow.pojos.FlowTasksObject;

import java.util.List;

public class FlowTaskEntry extends FlowTasksObject {

    public static FlowTaskEntry from(
        String companyId,
        String flowKey,
        long flowVersion,
        Task task,
        String parentId,
        int position
    ) {
        FlowTaskEntry entry = new FlowTaskEntry();
        entry.companyId = companyId;
        entry.id = task.id();
        entry.key = task.key();
        entry.displayName = task.displayName();
        entry.type = task.getType();
        entry.flowKey = flowKey;
        entry.flowVersion = flowVersion;
        entry.parentId = parentId;
        entry.position = position;
        entry.inputs = DataJsonCodec.encodeJsonb(task.inputs());
        entry.outputs = DataJsonCodec.encodeJsonb(task.outputs());
        entry.properties = TaskPropertiesCodec.encodeJsonb(task);
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
