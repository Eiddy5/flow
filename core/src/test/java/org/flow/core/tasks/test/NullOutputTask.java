package org.flow.core.tasks.test;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.flow.plugin.api.annotations.Plugin;
import org.flow.core.models.tasks.RunnableTask;
import org.flow.core.models.tasks.Task;
import org.flow.core.runners.RunContext;

import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin
public class NullOutputTask extends Task implements RunnableTask<NullOutputTask.Output> {

    @Override
    public Output run(RunContext runContext) throws Exception {
        return Output.builder().build();
    }

    @Builder
    @Getter
    public static class Output implements org.flow.core.models.tasks.Output {
        @JsonInclude(JsonInclude.Include.ALWAYS)
        private String value;
    }
}
