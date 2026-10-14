package org.flow.core.models;

import java.util.Optional;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.flow.core.models.annotations.Plugin;

class PluginTest {

    @Test
    void shouldReturnTrueForInternal() {
        Assertions.assertTrue(org.flow.core.models.Plugin.isInternal(TestPlugin.class));
    }

    @Test
    void shouldReturnTrueForPrimary() {
        Assertions.assertTrue(org.flow.core.models.Plugin.isPrimary(TestPlugin.class));
    }

    @Test
    void shouldReturnPluginId() {
        Assertions.assertEquals(Optional.of("test"), org.flow.core.models.Plugin.getId(TestPlugin.class));
    }

    @Plugin(internal = true, priority = Plugin.Priority.PRIMARY)
    @Plugin.Id("test")
    public static class TestPlugin implements org.flow.core.models.Plugin {

    }
}