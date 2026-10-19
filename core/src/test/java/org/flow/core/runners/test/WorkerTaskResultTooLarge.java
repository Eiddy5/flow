package org.flow.core.runners.test;

import java.util.Arrays;

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
public class WorkerTaskResultTooLarge extends Task implements RunnableTask<WorkerTaskResultTooLarge.Output> {

    @Override
    public Output run(RunContext runContext) throws Exception {
        char[] chars = new char[1100000];
        Arrays.fill(chars, 'a');

        return Output.builder().value(new String(chars)).build();
    }

    @Builder
    @Getter
    public static class Output implements org.flow.core.models.tasks.Output {
        public String value;
    }
}
