package org.flow.plugin.core.kv;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
class GetTest {

    static final String TEST_KV_KEY = "test-key";

    @Inject
    TestRunContextFactory runContextFactory;

    @Test
    void shouldGetGivenExistingKey() throws Exception {
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

        var value = Map.of("date", Instant.now().truncatedTo(ChronoUnit.MILLIS), "int", 1, "string", "string");

        Get get = Get.builder()
            .id(Get.class.getSimpleName())
            .type(Get.class.getName())
            .namespace(Property.ofExpression("{{ inputs.namespace }}"))
            .key(Property.ofExpression("{{ inputs.key }}"))
            .build();

        final KVStore kv = runContext.namespaceKv(namespaceId);

        // When
        kv.put(TEST_KV_KEY, new KVValueAndMetadata(null, value));

        // Then
        Get.Output run = get.run(runContext);
        assertThat(run.getValue()).isEqualTo(value);
    }

    @Test
    void shouldGetGivenExistingKeyWithInheritance() throws Exception {
        // Given
        String namespaceId = "org.flow." + IdUtils.create();
        RunContext runContext = this.runContextFactory.of(
            namespaceId, Map.of(
                "inputs", Map.of(
                    "key", TEST_KV_KEY
                )
            )
        );

        var value = Map.of("date", Instant.now().truncatedTo(ChronoUnit.MILLIS), "int", 1, "string", "string");

        Get get = Get.builder()
            .id(Get.class.getSimpleName())
            .type(Get.class.getName())
            .key(Property.ofExpression("{{ inputs.key }}"))
            .build();

        final KVStore kv = runContext.namespaceKv("org.flow");

        // When
        kv.put(TEST_KV_KEY, new KVValueAndMetadata(null, value));

        // Then
        Get.Output run = get.run(runContext);
        assertThat(run.getValue()).isEqualTo(value);
    }

    @Test
    void shouldGetGivenNonExistingKey() throws Exception {
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

        Get get = Get.builder()
            .id(Get.class.getSimpleName())
            .type(Get.class.getName())
            .namespace(Property.ofValue(namespaceId))
            .key(Property.ofValue("my-key"))
            .build();

        // When
        Get.Output run = get.run(runContext);

        // Then
        assertThat(run.getValue()).isNull();

        Get finalGet = get.toBuilder().errorOnMissing(Property.ofValue(true)).build();
        NoSuchElementException noSuchElementException = Assertions.assertThrows(NoSuchElementException.class, () -> finalGet.run(runContext));
        assertThat(noSuchElementException.getMessage()).isEqualTo("No value found for key 'my-key' in namespace '" + namespaceId + "' and `errorOnMissing` is set to true");
    }
}
