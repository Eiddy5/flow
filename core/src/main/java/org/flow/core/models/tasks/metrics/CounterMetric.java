package org.flow.core.models.tasks.metrics;

import java.util.Map;
import java.util.stream.Stream;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.models.executions.AbstractMetricEntry;
import org.flow.core.models.executions.metrics.Counter;
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
public class CounterMetric extends AbstractMetric {
    public static final String TYPE = "counter";

    @NotNull
    @EqualsAndHashCode.Exclude
    private Property<Double> value;

    @Override
    public AbstractMetricEntry<?> toMetric(RunContext runContext) throws IllegalVariableEvaluationException {
        String name = runContext.render(this.name).as(String.class).orElseThrow();
        Double value = runContext.render(this.value).as(Double.class).orElseThrow();
        String description = runContext.render(this.description).as(String.class).orElse(null);
        Map<String, String> tags = runContext.render(this.tags).asMap(String.class, String.class);
        String[] tagsAsStrings = tags.entrySet().stream()
            .flatMap(e -> Stream.of(e.getKey(), e.getValue()))
            .toArray(String[]::new);

        return Counter.of(name, description, value, tagsAsStrings);
    }

    public String getType() {
        return TYPE;
    }
}
