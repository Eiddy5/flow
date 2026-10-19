package org.flow.core.plugins;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

import org.flow.plugin.api.Plugin;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PluginScannerTest {
    @Test
    void scanPlugins() throws URISyntaxException {
        Path plugins = Paths.get(Objects.requireNonNull(PluginScannerTest.class.getClassLoader().getResource("plugins")).toURI());

        PluginScanner pluginScanner = new PluginScanner(PluginScannerTest.class.getClassLoader());
        List<RegisteredPlugin> scan = pluginScanner.scan(plugins);

        assertThat(scan.size()).isEqualTo(2);
        RegisteredPlugin templatePlugin = scan
            .stream()
            .filter(rp -> rp.group().equals("org.flow.plugin.templates"))
            .findFirst()
            .orElseThrow();
        assertThat(templatePlugin.getManifest().getMainAttributes().getValue("X-Flow-Group")).isEqualTo("org.flow.plugin.templates");
        assertThat(templatePlugin.allClass()).extracting(Class::getName)
            .containsExactly("org.flow.plugin.templates.ExampleTask");
        for (RegisteredPlugin plugin : scan) {
            assertThat(plugin.allClass()).isNotEmpty().allSatisfy(type ->
                assertThat(Plugin.class.isAssignableFrom(type)).isTrue());
        }
    }

    @Test
    void scanCore() {
        PluginScanner pluginScanner = new PluginScanner(PluginScannerTest.class.getClassLoader());
        RegisteredPlugin scan = pluginScanner.scan();
        assertThat(scan.getManifest().getMainAttributes().getValue("X-Flow-Group")).isEqualTo("org.flow.plugin.core");
    }
}
