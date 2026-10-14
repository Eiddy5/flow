package org.flow.executor.testkit;

import java.util.List;

import org.flow.core.models.flows.Concurrency;
import org.flow.core.models.flows.Flow;
import org.flow.core.models.flows.FlowWithSource;
import org.flow.core.models.tasks.Task;
import org.flow.core.serializers.YamlParser;
import org.flow.core.utils.IdUtils;
import org.flow.plugin.core.log.Log;

/**
 * Builders-first flow fixtures for executor unit tests.
 */
public final class Flows {
    public static final String TENANT = "main";
    public static final String NAMESPACE = "org.flow.tests";

    private Flows() {
        // utility class pattern
    }

    /** The cheapest real task: a Log with a fixed message. */
    public static Log log() {
        return log("log");
    }

    public static Log log(String id) {
        return Log.builder().id(id).type(Log.class.getName()).message("hello").build();
    }

    public static FlowWithSource of(Task... tasks) {
        return of(builder(tasks).build());
    }

    public static FlowWithSource withConcurrency(Concurrency concurrency, Task... tasks) {
        return of(builder(tasks).concurrency(concurrency).build());
    }

    public static Flow.FlowBuilder<?, ?> builder(Task... tasks) {
        return Flow.builder()
            .tenantId(TENANT)
            .namespace(NAMESPACE)
            .id("test-" + IdUtils.create())
            .revision(1)
            .tasks(List.of(tasks));
    }

    public static FlowWithSource of(Flow flow) {
        return FlowWithSource.of(flow, "");
    }

    /**
     * Parse a YAML flow source (static Jackson, no Micronaut — core task types resolve through
     * the {@code DefaultPluginRegistry} classpath fallback) and stamp the test tenant.
     */
    public static FlowWithSource yaml(String source) {
        Flow parsed = YamlParser.parse(source, Flow.class);
        return FlowWithSource.of(parsed.toBuilder().tenantId(TENANT).revision(1).build(), source);
    }
}
