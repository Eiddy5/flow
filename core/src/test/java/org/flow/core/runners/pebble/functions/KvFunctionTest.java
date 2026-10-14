package org.flow.core.runners.pebble.functions;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.runners.KVMetadataStateStore;
import org.flow.core.runners.VariableRenderer;
import org.flow.core.storages.StorageInterface;
import org.flow.core.storages.kv.InternalKVStore;
import org.flow.core.storages.kv.KVMetadata;
import org.flow.core.storages.kv.KVStore;
import org.flow.core.storages.kv.KVValueAndMetadata;
import org.flow.core.utils.TestsUtils;

import jakarta.inject.Inject;

import static org.flow.core.runners.pebble.functions.FunctionTestUtils.getVariables;
import static org.assertj.core.api.Assertions.assertThat;

@FlowTest(startRunner = true)
public class KvFunctionTest {
    @Inject
    private KVMetadataStateStore kvMetadataStateStore;

    @Inject
    private StorageInterface storageInterface;

    @Inject
    VariableRenderer variableRenderer;

    @Test
    void shouldGetValueFromKVGivenExistingKey() throws IllegalVariableEvaluationException, IOException {
        // Given
        String tenant = TestsUtils.randomTenant(this.getClass().getSimpleName());
        KVStore kv = new InternalKVStore(tenant, "org.flow.tests", storageInterface, kvMetadataStateStore);
        kv.put("my-key", new KVValueAndMetadata(null, Map.of("field", "value")));

        Map<String, Object> variables = getVariables(tenant, "org.flow.tests");

        // When
        String rendered = variableRenderer.render("{{ kv('my-key') }}", variables);

        // Then
        assertThat(rendered).isEqualTo("{\"field\":\"value\"}");
    }

    @Test
    void shouldGetValueFromKVGivenExistingKeyWithInheritance() throws IllegalVariableEvaluationException, IOException {
        // Given
        String tenant = TestsUtils.randomTenant(this.getClass().getSimpleName());
        KVStore kv = new InternalKVStore(tenant, "my.company", storageInterface, kvMetadataStateStore);
        kv.put("my-key", new KVValueAndMetadata(null, Map.of("field", "value")));

        KVStore firstKv = new InternalKVStore(tenant, "my", storageInterface, kvMetadataStateStore);
        firstKv.put("my-key", new KVValueAndMetadata(null, Map.of("field", "firstValue")));

        Map<String, Object> variables = getVariables(tenant, "my.company.team");

        // When
        String rendered = variableRenderer.render("{{ kv('my-key') }}", variables);

        // Then
        assertThat(rendered).isEqualTo("{\"field\":\"value\"}");
    }

    @Test
    void shouldNotGetValueFromKVWithGivenNamespaceAndInheritance() throws IOException {
        // Given
        String tenant = TestsUtils.randomTenant(this.getClass().getSimpleName());
        KVStore kv = new InternalKVStore(tenant, "kv", storageInterface, kvMetadataStateStore);
        kv.put("my-key", new KVValueAndMetadata(null, Map.of("field", "value")));

        Map<String, Object> variables = getVariables(tenant, "my.company.team");

        // When
        Assertions.assertThrows(IllegalVariableEvaluationException.class, () -> variableRenderer.render("{{ kv('my-key', namespace='kv.inherited') }}", variables));
    }

    @Test
    void shouldGetValueFromKVGivenExistingAndNamespace() throws IllegalVariableEvaluationException, IOException {
        // Given
        String tenant = TestsUtils.randomTenant(this.getClass().getSimpleName());
        KVStore kv = new InternalKVStore(tenant, "kv", storageInterface, kvMetadataStateStore);
        kv.put("my-key", new KVValueAndMetadata(null, Map.of("field", "value")));

        Map<String, Object> variables = getVariables(tenant, "org.flow.tests");

        // When
        String rendered = variableRenderer.render("{{ kv('my-key', namespace='kv') }}", variables);

        // Then
        assertThat(rendered).isEqualTo("{\"field\":\"value\"}");
    }

    @Test
    void shouldGetEmptyGivenNonExistingKeyAndErrorOnMissingFalse() throws IllegalVariableEvaluationException {
        // Given
        String tenant = TestsUtils.randomTenant(this.getClass().getSimpleName());
        Map<String, Object> variables = getVariables(tenant, "org.flow.tests");

        // When
        String rendered = variableRenderer.render("{{ kv('my-key', errorOnMissing=false) }}", variables);

        // Then
        assertThat(rendered).isEqualTo("");
    }

    @Test
    void shouldThrowOrGetEmptyIfExpiredDependingOnErrorOnMissing() throws IOException, IllegalVariableEvaluationException {
        String tenant = TestsUtils.randomTenant();
        String namespace = TestsUtils.randomNamespace();
        Map<String, Object> variables = getVariables(tenant, namespace);

        KVStore kv = new InternalKVStore(tenant, namespace, storageInterface, kvMetadataStateStore);
        kv.put("my-expired-key", new KVValueAndMetadata(new KVMetadata(null, Instant.now().minus(1, ChronoUnit.HOURS)), "anyValue"));

        String rendered = variableRenderer.render("{{ kv('my-expired-key', errorOnMissing=false) }}", variables);
        assertThat(rendered).isEqualTo("");

        kv.put("another-expired-key", new KVValueAndMetadata(new KVMetadata(null, Instant.now().minus(1, ChronoUnit.HOURS)), "anyValue"));

        IllegalVariableEvaluationException exception = Assertions
            .assertThrows(IllegalVariableEvaluationException.class, () -> variableRenderer.render("{{ kv('another-expired-key') }}", variables));

        assertThat(exception.getMessage()).isEqualTo("io.pebbletemplates.pebble.error.PebbleException: The requested value has expired ({{ kv('another-expired-key') }}:1)");
    }

    @Test
    void shouldFailGivenNonExistingKeyAndErrorOnMissingTrue() {
        // Given
        String tenant = TestsUtils.randomTenant(this.getClass().getSimpleName());
        Map<String, Object> variables = getVariables(tenant, "org.flow.tests");

        // When
        IllegalVariableEvaluationException exception = Assertions.assertThrows(IllegalVariableEvaluationException.class, () ->
        {
            variableRenderer.render("{{ kv('my-key', errorOnMissing=true) }}", variables);
        });

        // Then
        assertThat(exception.getMessage())
            .isEqualTo("io.pebbletemplates.pebble.error.PebbleException: The key 'my-key' does not exist in the namespace 'org.flow.tests'. ({{ kv('my-key', errorOnMissing=true) }}:1)");
    }

    @Test
    void shouldFailGivenNonExistingKeyUsingDefaults() {
        // Given
        String tenant = TestsUtils.randomTenant(this.getClass().getSimpleName());
        Map<String, Object> variables = getVariables(tenant, "org.flow.tests");
        // When
        IllegalVariableEvaluationException exception = Assertions.assertThrows(IllegalVariableEvaluationException.class, () -> variableRenderer.render("{{ kv('my-key') }}", variables));

        // Then
        assertThat(exception.getMessage())
            .isEqualTo("io.pebbletemplates.pebble.error.PebbleException: The key 'my-key' does not exist in the namespace 'org.flow.tests'. ({{ kv('my-key') }}:1)");
    }

}
