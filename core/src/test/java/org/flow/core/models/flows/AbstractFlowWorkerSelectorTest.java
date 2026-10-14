package org.flow.core.models.flows;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.tasks.Task;
import org.flow.core.models.tasks.WorkerQueueFallback;
import org.flow.core.models.tasks.WorkerSelector;
import org.flow.plugin.core.log.Log;

import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
class AbstractFlowUsingTest {

    @Inject
    private Validator validator;

    @Test
    void shouldAcceptValidTagsOnFlow() {
        Flow flow = Flow.builder()
            .id("test")
            .namespace("org.flow.tests")
            .workerSelector(new WorkerSelector(List.of("docker", "linux-amd64"), null))
            .tasks(List.<Task> of(Log.builder().id("log").type(Log.class.getName()).message("hello").build()))
            .build();

        Set<ConstraintViolation<Flow>> violations = validator.validate(flow);
        assertThat(violations).isEmpty();
    }

    @Test
    void shouldRejectTagWithUppercase() {
        Flow flow = Flow.builder()
            .id("test")
            .namespace("org.flow.tests")
            .workerSelector(new WorkerSelector(List.of("Docker"), null))
            .tasks(List.<Task> of(Log.builder().id("log").type(Log.class.getName()).message("hello").build()))
            .build();

        Set<ConstraintViolation<Flow>> violations = validator.validate(flow);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().startsWith("workerSelector.tags"));
    }

    @Test
    void shouldRejectTagWithInvalidChars() {
        Flow flow = Flow.builder()
            .id("test")
            .namespace("org.flow.tests")
            .workerSelector(new WorkerSelector(List.of("linux_amd64"), null))
            .tasks(List.<Task> of(Log.builder().id("log").type(Log.class.getName()).message("hello").build()))
            .build();

        Set<ConstraintViolation<Flow>> violations = validator.validate(flow);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().startsWith("workerSelector.tags"));
    }

    @Test
    void shouldRejectTagWithLeadingHyphen() {
        Flow flow = Flow.builder()
            .id("test")
            .namespace("org.flow.tests")
            .workerSelector(new WorkerSelector(List.of("-docker"), null))
            .tasks(List.<Task> of(Log.builder().id("log").type(Log.class.getName()).message("hello").build()))
            .build();

        Set<ConstraintViolation<Flow>> violations = validator.validate(flow);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().startsWith("workerSelector.tags"));
    }

    @Test
    void shouldRejectMoreThanTwentyTags() {
        List<String> tags = java.util.stream.IntStream.range(0, 21).mapToObj(i -> "tag" + i).toList();
        Flow flow = Flow.builder()
            .id("test")
            .namespace("org.flow.tests")
            .workerSelector(new WorkerSelector(tags, null))
            .tasks(List.<Task> of(Log.builder().id("log").type(Log.class.getName()).message("hello").build()))
            .build();

        Set<ConstraintViolation<Flow>> violations = validator.validate(flow);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("workerSelector.tags"));
    }

    @Test
    void shouldRejectFallbackWithoutTags() {
        Flow flow = Flow.builder()
            .id("test")
            .namespace("org.flow.tests")
            .workerSelector(new WorkerSelector(List.of(), WorkerQueueFallback.WAIT))
            .tasks(List.<Task> of(Log.builder().id("log").type(Log.class.getName()).message("hello").build()))
            .build();

        Set<ConstraintViolation<Flow>> violations = validator.validate(flow);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().contains("workerSelector"));
    }
}
