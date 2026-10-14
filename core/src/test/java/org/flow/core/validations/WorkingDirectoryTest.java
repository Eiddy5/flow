package org.flow.core.validations;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.property.Property;
import org.flow.core.models.tasks.WorkerSelector;
import org.flow.core.models.validations.ModelValidator;
import org.flow.plugin.core.flow.Pause;
import org.flow.plugin.core.flow.WorkingDirectory;
import org.flow.plugin.core.log.Log;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
public class WorkingDirectoryTest {
    @Inject
    private ModelValidator modelValidator;

    @Test
    void workingDirectoryValid() {
        var workingDirectory = WorkingDirectory.builder()
            .id("workingDir")
            .type(WorkingDirectory.class.getName())
            .tasks(
                List.of(
                    Log.builder()
                        .id("log")
                        .type(Log.class.getName())
                        .message("Hello World")
                        .build()
                )
            )
            .build();

        assertThat(modelValidator.isValid(workingDirectory).isPresent()).isFalse();
    }

    @Test
    void workingDirectoryInvalid() {
        // empty list of tasks
        var workingDirectory = WorkingDirectory.builder()
            .id("workingDir")
            .type(WorkingDirectory.class.getName())
            .build();

        assertThat(modelValidator.isValid(workingDirectory).isPresent()).isTrue();
        assertThat(modelValidator.isValid(workingDirectory).get().getMessage()).contains("The 'tasks' property cannot be empty");

        // flowable task
        workingDirectory = WorkingDirectory.builder()
            .id("workingDir")
            .type(WorkingDirectory.class.getName())
            .tasks(
                List.of(
                    Pause.builder()
                        .id("pause")
                        .type(Pause.class.getName())
                        .pauseDuration(Property.ofValue(Duration.ofSeconds(1L)))
                        .build()
                )
            )
            .build();

        assertThat(modelValidator.isValid(workingDirectory).isPresent()).isTrue();
        assertThat(modelValidator.isValid(workingDirectory).get().getMessage()).contains("Only runnable tasks are allowed as children of a WorkingDirectory task");

        // worker group at the subtasks level
        workingDirectory = WorkingDirectory.builder()
            .id("workingDir")
            .type(WorkingDirectory.class.getName())
            .tasks(
                List.of(
                    Log.builder()
                        .id("log")
                        .type(Log.class.getName())
                        .message("Hello World")
                        .workerSelector(new WorkerSelector(List.of("toto"), null))
                        .build()
                )
            )
            .build();

        assertThat(modelValidator.isValid(workingDirectory).isPresent()).isTrue();
        assertThat(modelValidator.isValid(workingDirectory).get().getMessage())
            .contains("Cannot set routing requirements (workerSelector) on WorkingDirectory sub-tasks");

    }
}
