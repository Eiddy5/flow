package org.flow.plugin.api;

import java.util.Optional;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.flow.plugin.api.annotations.Plugin;

class PluginTest {

    @Test
    void shouldReturnTrueForInternal() {
        Assertions.assertTrue(org.flow.plugin.api.Plugin.isInternal(TestPlugin.class));
    }

    @Test
    void shouldReturnTrueForPrimary() {
        Assertions.assertTrue(org.flow.plugin.api.Plugin.isPrimary(TestPlugin.class));
    }

    @Test
    void shouldReturnPluginId() {
        Assertions.assertEquals(Optional.of("test"), org.flow.plugin.api.Plugin.getId(TestPlugin.class));
    }

    @Plugin(internal = true, priority = Plugin.Priority.PRIMARY)
    @Plugin.Id("test")
    public static class TestPlugin implements org.flow.plugin.api.Plugin {

    }
}