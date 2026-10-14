package org.flow.core.plugins;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@MicronautTest
class PluginConfigurationTest {

    @Inject
    private List<PluginConfiguration> configurations;

    @Test
    void testInjectEachProperty() {
        assertThat(this.configurations).contains(new PluginConfiguration(0, "org.flow.plugin.Test0", Map.of("prop0", "value0")));
        assertThat(this.configurations).contains(new PluginConfiguration(1, "org.flow.plugin.Test1", Map.of("prop1", "value1")));
        assertThat(this.configurations).contains(new PluginConfiguration(2, "org.flow.plugin.Test2", Map.of("prop2", "value2")));
    }

}