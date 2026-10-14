package org.flow.core.docs;

import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;

import org.flow.core.models.flows.Flow;
import org.flow.core.plugins.PluginScanner;
import org.flow.core.plugins.RegisteredPlugin;
import org.flow.plugin.core.http.Request;
import org.flow.plugin.core.storage.Delete;

import static org.assertj.core.api.Assertions.assertThat;

class PluginTest {

    @Test
    void titleForFallsBackToThePluginsOwnTitleWhenTheClassIsNotInARegisteredSubGroup() {
        // Real-world equivalent: org.flow.plugin.mongodb.Trigger and
        // org.flow.plugin.debezium.mongodb.Trigger are both conventionally named `Trigger` and both
        // live in a package ending in "mongodb" — a package-name-derived guess would collide the two.
        // Each plugin's own declared title (from its metadata/index.yaml, surfaced via
        // RegisteredPlugin#title) must disambiguate them regardless of package shape.
        RegisteredPlugin mongodb = pluginWithTitle("MongoDB");
        RegisteredPlugin debeziumMongodb = pluginWithTitle("Debezium MongoDB");

        // The concrete class only matters for its package here; it isn't a real trigger of either
        // plugin, it just stands in for "some class that isn't part of a declared subgroup".
        String mongodbTitle = Plugin.titleFor(mongodb, Flow.class);
        String debeziumMongodbTitle = Plugin.titleFor(debeziumMongodb, Flow.class);

        assertThat(mongodbTitle).isEqualTo("MongoDB");
        assertThat(debeziumMongodbTitle).isEqualTo("Debezium MongoDB");
        assertThat(mongodbTitle).isNotEqualTo(debeziumMongodbTitle);
    }

    @Test
    void titleForUsesTheSubGroupsOwnDeclaredTitleWhenPresent() {
        RegisteredPlugin core = pluginWithTitleAndGroup("core", "org.flow.plugin.core");

        // org.flow.plugin.core.http declares @PluginSubGroup(title = "HTTP", ...)
        assertThat(Plugin.titleFor(core, Request.class)).isEqualTo("HTTP");
    }

    @Test
    void titleForFallsBackToTheRawSubGroupSegmentWhenTheSubGroupDeclaresNoTitle() {
        RegisteredPlugin core = pluginWithTitleAndGroup("core", "org.flow.plugin.core");

        // org.flow.plugin.core.storage declares @PluginSubGroup(categories = CORE) with no title.
        assertThat(Plugin.titleFor(core, Delete.class)).isEqualTo("storage");
    }

    @Test
    void ofExposesAliasesOnPluginElementMetadata() {
        RegisteredPlugin core = new PluginScanner(PluginTest.class.getClassLoader()).scan();
        Plugin result = Plugin.of(core, null);

        assertThat(result.getAliases()).isNotEmpty();

        Plugin.PluginElementMetadata httpRequest = result.getTasks().stream()
            .filter(t -> t.cls().equals("org.flow.plugin.core.http.Request"))
            .findFirst()
            .orElseThrow();
        assertThat(httpRequest.aliases()).containsExactly("org.flow.plugin.fs.http.Request");

        Plugin.PluginElementMetadata delete = result.getTasks().stream()
            .filter(t -> t.cls().equals(Delete.class.getName()))
            .findFirst()
            .orElseThrow();
        assertThat(delete.aliases()).isNull();
    }


    private static RegisteredPlugin pluginWithTitle(String title) {
        return pluginWithTitleAndGroup(title, null);
    }

    private static RegisteredPlugin pluginWithTitleAndGroup(String title, String group) {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("X-Flow-Title", title);
        if (group != null) {
            manifest.getMainAttributes().putValue("X-Flow-Group", group);
        }

        return RegisteredPlugin.builder().manifest(manifest).build();
    }
}
