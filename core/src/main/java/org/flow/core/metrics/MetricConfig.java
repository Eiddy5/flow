package org.flow.core.metrics;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.flow.core.server.ServiceType;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.convert.format.MapFormat;
import lombok.*;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@ConfigurationProperties("flow.metrics")
public class MetricConfig {
    String prefix;

    @MapFormat(transformation = MapFormat.MapTransformation.FLAT)
    Map<String, String> tags;

    /**
     * {@link org.flow.core.models.Label} keys included to metrics.
     */
    List<String> labels;

    Map<ServiceType, Set<String>> sharedServiceInstanceMetrics = Map.of();
}
