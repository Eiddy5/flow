package org.flow.plugin.core.kv;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.flow.core.context.TestRunContextFactory;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.property.Property;
import org.flow.core.runners.RunContext;
import org.flow.core.storages.kv.KVStore;
import org.flow.core.storages.kv.KVValueAndMetadata;
import org.flow.core.utils.IdUtils;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
class GetKeysTest {
    static final String TEST_KEY_PREFIX_TEST = "test";

    @Inject
    TestRunContextFactory runContextFactory;

    @Test
    void shouldGetAllKeys() throws Exception {
        // Given
        String namespace = IdUtils.create();
        RunContext runContext = this.runContextFactory.of(namespace);

        GetKeys getKeys = GetKeys.builder()
            .id(GetKeys.class.getSimpleName())
            .type(GetKeys.class.getName())
            .build();

        final KVStore kv = runContext.namespaceKv(namespace);
        kv.put("test-key", new KVValueAndMetadata(null, "value"));
        kv.put("test-second-key", new KVValueAndMetadata(null, "value"));
        kv.put("another-key", new KVValueAndMetadata(null, "value"));

        // When
        GetKeys.Output run = getKeys.run(runContext);

        // Then
        assertThat(run.getKeys()).containsExactlyInAnyOrder("test-key", "test-second-key", "another-key");
    }

    @Test
    void shouldGetKeysGivenMatchingPrefix() throws Exception {
        // Given
        String namespace = IdUtils.create();
        RunContext runContext = this.runContextFactory.of(
            namespace,
            Map.of("inputs", Map.of("prefix", TEST_KEY_PREFIX_TEST))
        );

        GetKeys getKeys = GetKeys.builder()
            .id(GetKeys.class.getSimpleName())
            .type(GetKeys.class.getName())
            .prefix(Property.ofExpression("{{ inputs.prefix }}"))
            .build();

        final KVStore kv = runContext.namespaceKv(namespace);
        kv.put(TEST_KEY_PREFIX_TEST + "-key", new KVValueAndMetadata(null, "value"));
        kv.put(TEST_KEY_PREFIX_TEST + "-second-key", new KVValueAndMetadata(null, "value"));
        kv.put("another-key", new KVValueAndMetadata(null, "value"));

        // When
        GetKeys.Output run = getKeys.run(runContext);

        // Then
        assertThat(run.getKeys()).containsExactlyInAnyOrder(TEST_KEY_PREFIX_TEST + "-key", TEST_KEY_PREFIX_TEST + "-second-key");
    }

    @Test
    void shouldGetNoKeysGivenEmptyKeyStore() throws Exception {
        // Given
        String namespace = IdUtils.create();
        RunContext runContext = this.runContextFactory.of(
            namespace,
            Map.of("inputs", Map.of("prefix", TEST_KEY_PREFIX_TEST))
        );

        GetKeys getKeys = GetKeys.builder()
            .id(GetKeys.class.getSimpleName())
            .type(GetKeys.class.getName())
            .prefix(Property.ofExpression("{{ inputs.prefix }}"))
            .build();

        // When
        GetKeys.Output run = getKeys.run(runContext);

        // Then
        assertThat(run.getKeys()).isEmpty();
    }
}
