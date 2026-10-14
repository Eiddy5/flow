package org.flow.core.plugins;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ClassTypeIdentifierTest {
    @Test
    void caseMatters() {
        String identifier = "org.flow.core.plugins.serdes.PluginDeserializerTest.TestPlugin";
        assertThat(DefaultPluginRegistry.ClassTypeIdentifier.create(identifier).type()).isEqualTo(identifier);
    }
}
