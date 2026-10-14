package org.flow.core.plugins;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PluginIdentifierTest {

    @Test
    void shouldParseVersionedIdentifierGivenVersion() {
        Pair<String, String> pair = PluginIdentifier.parseIdentifier("org.flow.plugin.Test:1.0.0");
        assertThat(pair).isEqualTo(Pair.of("org.flow.plugin.Test", "1.0.0"));
    }

    @Test
    void shouldParseVersionedIdentifierGivenNoVersion() {
        Pair<String, String> pair = PluginIdentifier.parseIdentifier("org.flow.plugin.Test");
        assertThat(pair).isEqualTo(Pair.of("org.flow.plugin.Test", null));
    }
}