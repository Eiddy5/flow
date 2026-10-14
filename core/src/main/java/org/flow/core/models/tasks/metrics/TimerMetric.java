package org.flow.core.models.tasks.metrics;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.models.executions.AbstractMetricEntry;
import org.flow.core.models.executions.metrics.Timer;
import org.flow.core.models.property.Property;
import org.flow.core.runners.RunContext;

import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class TimerMetric extends AbstractMetric {
    public static final String TYPE = "timer";

    @NotNull
    @EqualsAndHashCode.Exclude
    private Property<Duration> value;

    @Override
    public AbstractMetricEntry<?> toMetric(RunContext runContext) throws IllegalVariableEvaluationException {
        String name = runContext.render(this.name).as(String.class).orElseThrow();
        Duration value = runContext.render(this.value).as(Duration.class).orElseThrow();
        String description = runContext.render(this.description).as(String.class).orElse(null);
        Map<String, String> tags = runContext.render(this.tags).asMap(String.class, String.class);
        String[] tagsAsStrings = tags.entrySet().stream()
            .flatMap(e -> Stream.of(e.getKey(), e.getValue()))
            .toArray(String[]::new);

        return Timer.of(name, description, value, tagsAsStrings);
    }

    public String getType() {
        return TYPE;
    }
}
