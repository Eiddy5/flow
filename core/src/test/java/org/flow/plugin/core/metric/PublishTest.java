package org.flow.plugin.core.metric;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.flow.core.context.TestRunContextFactory;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.property.Property;
import org.flow.core.models.tasks.metrics.CounterMetric;
import org.flow.core.models.tasks.metrics.GaugeMetric;
import org.flow.core.models.tasks.metrics.TimerMetric;
import org.flow.core.runners.RunContext;
import org.flow.core.utils.TestsUtils;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
public class PublishTest {
    @Inject
    private TestRunContextFactory runContextFactory;

    @Test
    void run() throws Exception {
        var publish = Publish.builder()
            .id(Publish.class.getSimpleName())
            .type(Publish.class.getName())
            .metrics(
                Property.ofValue(
                    List.of(
                        CounterMetric.builder()
                            .value(Property.ofValue(1.0))
                            .name(Property.ofValue("counter"))
                            .build(),
                        TimerMetric.builder()
                            .value(Property.ofValue(Duration.parse("PT5H")))
                            .name(Property.ofValue("timer"))
                            .build(),
                        GaugeMetric.builder()
                            .value(Property.ofValue(1.0))
                            .name(Property.ofValue("gauge"))
                            .build()
                    )
                )
            )
            .build();

        final RunContext runContext = TestsUtils.mockRunContext(this.runContextFactory, publish, Map.of("inputs", Map.of("test", "counter")));

        publish.run(runContext);

        assertThat(runContext.metrics().size()).isEqualTo(3);
    }

}
