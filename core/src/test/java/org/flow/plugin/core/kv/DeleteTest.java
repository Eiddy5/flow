package org.flow.plugin.core.kv;

import java.util.Map;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Assertions;
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
class DeleteTest {
    static final String TEST_KV_KEY = "test-key";

    @Inject
    TestRunContextFactory runContextFactory;

    @Test
    void shouldOutputTrueGivenExistingKey() throws Exception {
        // Given
        String namespaceId = "org.flow." + IdUtils.create();
        RunContext runContext = this.runContextFactory.of(
            namespaceId, Map.of(
                "inputs", Map.of(
                    "key", TEST_KV_KEY,
                    "namespace", namespaceId
                )
            )
        );

        Delete delete = Delete.builder()
            .id(Delete.class.getSimpleName())
            .type(Delete.class.getName())
            .namespace(Property.ofExpression("{{ inputs.namespace }}"))
            .key(Property.ofExpression("{{ inputs.key }}"))
            .build();

        final KVStore kv = runContext.namespaceKv(namespaceId);
        kv.put(TEST_KV_KEY, new KVValueAndMetadata(null, "value"));

        // When
        Delete.Output run = delete.run(runContext);

        // Then
        assertThat(run.isDeleted()).isTrue();
    }

    @Test
    void shouldOutputFalseGivenNonExistingKey() throws Exception {
        // Given
        String namespaceId = "org.flow." + IdUtils.create();
        RunContext runContext = this.runContextFactory.of(
            namespaceId, Map.of(
                "inputs", Map.of(
                    "key", TEST_KV_KEY,
                    "namespace", namespaceId
                )
            )
        );

        Delete delete = Delete.builder()
            .id(Delete.class.getSimpleName())
            .type(Delete.class.getName())
            .namespace(Property.ofValue(namespaceId))
            .key(Property.ofValue("my-key"))
            .build();

        // When
        Delete.Output run = delete.run(runContext);

        assertThat(run.isDeleted()).isFalse();

        Delete finalDelete = delete.toBuilder().errorOnMissing(Property.ofValue(true)).build();
        NoSuchElementException noSuchElementException = Assertions.assertThrows(NoSuchElementException.class, () -> finalDelete.run(runContext));
        assertThat(noSuchElementException.getMessage()).isEqualTo("No value found for key 'my-key' in namespace '" + namespaceId + "' and `errorOnMissing` is set to true");
    }
}
