package org.cses.flow.extensions.flow;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.tasks.OrchestrationTask;
import org.cses.flow.core.domains.tasks.Task;

import java.util.List;

@SuperBuilder
@NoArgsConstructor
public abstract class Branch extends Task implements OrchestrationTask {

    @NotNull
    @Builder.Default
    @Schema(
        title = "子任务",
        description = "由当前结构型任务直接拥有的有序子任务定义"
    )
    List<Task> tasks = List.of();

    public List<Task> tasks() {
        return tasks == null ? List.of() : List.copyOf(tasks);
    }

    @Override
    public List<Task> definitionChildren() {
        return tasks();
    }
}
