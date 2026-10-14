package org.flow.plugin.core.storage;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.property.Property;
import org.flow.core.runners.RunContext;
import org.flow.core.runners.RunContextFactory;
import org.flow.core.serializers.JacksonMapper;
import org.flow.core.storages.StorageInterface;

import jakarta.inject.Inject;

@FlowTest
class FilterItemsTest {
    private static final List<KeyValue> TEST_VALID_ITEMS = List.of(
        new KeyValue("k1", 1),
        new KeyValue("k2", 2),
        new KeyValue("k3", 3),
        new KeyValue("k4", 4)
    );

    private static final List<KeyValue> TEST_INVALID_ITEMS = List.of(
        new KeyValue("k1", 1),
        new KeyValue("k2", "dummy"),
        new KeyValue("k3", 3),
        new KeyValue("k4", 4)
    );

    @Inject
    RunContextFactory runContextFactory;

    @Inject
    StorageInterface storageInterface;

    @Test
    void shouldFilterGivenValidBooleanExpressionForInclude() throws Exception {
        // Given
        RunContext runContext = runContextFactory.of();

        FilterItems task = FilterItems
            .builder()
            .from(Property.ofValue(generateKeyValueFile(TEST_VALID_ITEMS, runContext).toString()))
            .filterCondition(" {{ value % 2 == 0 }} ")
            .filterType(Property.ofValue(FilterItems.FilterType.INCLUDE))
            .build();

        // When
        FilterItems.Output output = task.run(runContext);

        // Then
        Assertions.assertNotNull(output);
        Assertions.assertNotNull(output.getUri());
        Assertions.assertEquals(2, output.getDroppedItemsTotal());
        Assertions.assertEquals(4, output.getProcessedItemsTotal());
        assertFile(runContext, output, List.of(new KeyValue("k2", 2), new KeyValue("k4", 4)), KeyValue.class);
    }

    @Test
    void shouldFilterGivenValidBooleanExpressionForExclude() throws Exception {
        // Given
        RunContext runContext = runContextFactory.of();

        FilterItems task = FilterItems
            .builder()
            .from(Property.ofValue(generateKeyValueFile(TEST_VALID_ITEMS, runContext).toString()))
            .filterCondition(" {{ value % 2 == 0 }} ")
            .filterType(Property.ofValue(FilterItems.FilterType.EXCLUDE))
            .build();

        // When
        FilterItems.Output output = task.run(runContext);

        // Then
        Assertions.assertNotNull(output);
        Assertions.assertNotNull(output.getUri());
        Assertions.assertEquals(2, output.getDroppedItemsTotal());
        Assertions.assertEquals(4, output.getProcessedItemsTotal());
        assertFile(runContext, output, List.of(new KeyValue("k1", 1), new KeyValue("k3", 3)), KeyValue.class);
    }

    @Test
    void shouldThrowExceptionGivenInvalidRecordsForFail() throws Exception {
        // Given
        RunContext runContext = runContextFactory.of();

        FilterItems task = FilterItems
            .builder()
            .from(Property.ofValue(generateKeyValueFile(TEST_INVALID_ITEMS, runContext).toString()))
            .filterCondition(" {{ value % 2 == 0 }}")
            .filterType(Property.ofValue(FilterItems.FilterType.INCLUDE))
            .errorOrNullBehavior(Property.ofValue(FilterItems.ErrorOrNullBehavior.FAIL))
            .build();

        // When/Then
        Assertions.assertThrows(IllegalVariableEvaluationException.class, () -> task.run(runContext));
    }

    @Test
    void shouldFilterGivenInvalidRecordsForInclude() throws Exception {
        // Given
        RunContext runContext = runContextFactory.of();

        FilterItems task = FilterItems
            .builder()
            .from(Property.ofValue(generateKeyValueFile(TEST_INVALID_ITEMS, runContext).toString()))
            .filterCondition(" {{ value % 2 == 0 }}")
            .filterType(Property.ofValue(FilterItems.FilterType.INCLUDE))
            .errorOrNullBehavior(Property.ofValue(FilterItems.ErrorOrNullBehavior.INCLUDE))
            .build();

        // When
        FilterItems.Output output = task.run(runContext);

        // Then
        Assertions.assertNotNull(output);
        Assertions.assertNotNull(output.getUri());
        Assertions.assertEquals(2, output.getDroppedItemsTotal());
        Assertions.assertEquals(4, output.getProcessedItemsTotal());
        assertFile(runContext, output, List.of(new KeyValue("k2", "dummy"), new KeyValue("k4", 4)), KeyValue.class);
    }

    @Test
    void shouldFilterGivenInvalidRecordsForExclude() throws Exception {
        // Given
        RunContext runContext = runContextFactory.of();

        FilterItems task = FilterItems
            .builder()
            .from(Property.ofValue(generateKeyValueFile(TEST_INVALID_ITEMS, runContext).toString()))
            .filterCondition(" {{ value % 2 == 0 }}")
            .filterType(Property.ofValue(FilterItems.FilterType.INCLUDE))
            .errorOrNullBehavior(Property.ofValue(FilterItems.ErrorOrNullBehavior.EXCLUDE))
            .build();

        // When
        FilterItems.Output output = task.run(runContext);

        // Then
        Assertions.assertNotNull(output);
        Assertions.assertNotNull(output.getUri());
        Assertions.assertEquals(3, output.getDroppedItemsTotal());
        Assertions.assertEquals(4, output.getProcessedItemsTotal());
        assertFile(runContext, output, List.of(new KeyValue("k4", 4)), KeyValue.class);
    }

    @Test
    void shouldFilterWithNotMatchGivenNonBooleanValue() throws Exception {
        // Given
        RunContext runContext = runContextFactory.of();

        FilterItems task = FilterItems
            .builder()
            .from(Property.ofValue(generateKeyValueFile(TEST_VALID_ITEMS, runContext).toString()))
            .filterCondition("{{ value }}")
            .filterType(Property.ofValue(FilterItems.FilterType.INCLUDE))
            .errorOrNullBehavior(Property.ofValue(FilterItems.ErrorOrNullBehavior.FAIL))
            .build();

        // When
        FilterItems.Output output = task.run(runContext);

        // Then
        Assertions.assertNotNull(output);
        Assertions.assertNotNull(output.getUri());
        Assertions.assertEquals(0, output.getDroppedItemsTotal());
        Assertions.assertEquals(4, output.getProcessedItemsTotal());
        assertFile(runContext, output, TEST_VALID_ITEMS, KeyValue.class);
    }

    private static <T> void assertFile(final RunContext runContext,
        final FilterItems.Output output,
        final List<T> expected,
        final Class<T> type) throws IOException {
        try (InputStream resource = new java.io.BufferedInputStream(runContext.storage().getFile(output.getUri()), org.flow.core.serializers.FileSerde.BUFFER_SIZE)) {
            List<T> list = new java.util.ArrayList<>();
            org.flow.core.serializers.FileSerde.read(resource, row -> list.add(JacksonMapper.ofIon().convertValue(row, type)));
            Assertions.assertEquals(expected, list);
        }
    }

    private URI generateKeyValueFile(final List<?> items, RunContext runContext) throws IOException {
        Path path = runContext.workingDir().createTempFile(".ion");
        try (final BufferedWriter writer = Files.newBufferedWriter(path)) {
            items.forEach(object ->
            {
                try {
                    writer.write(JacksonMapper.ofIon().writeValueAsString(object));
                    writer.newLine();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        return runContext.storage().putFile(path.toFile());
    }

    private URI generateBinaryKeyValueFile(final List<?> items, RunContext runContext) throws IOException {
        Path path = runContext.workingDir().createTempFile(".ion");
        try (var output = new java.io.BufferedOutputStream(new java.io.FileOutputStream(path.toFile()), org.flow.core.serializers.FileSerde.BUFFER_SIZE)) {
            for (Object item : items) {
                org.flow.core.serializers.FileSerde.write(output, item);
            }
        }
        return runContext.storage().putFile(path.toFile());
    }

    @Test
    void shouldFilterBinaryIonGivenValidBooleanExpressionForInclude() throws Exception {
        // Given
        RunContext runContext = runContextFactory.of();

        FilterItems task = FilterItems
            .builder()
            .from(Property.ofValue(generateBinaryKeyValueFile(TEST_VALID_ITEMS, runContext).toString()))
            .filterCondition(" {{ value % 2 == 0 }} ")
            .filterType(Property.ofValue(FilterItems.FilterType.INCLUDE))
            .build();

        // When
        FilterItems.Output output = task.run(runContext);

        // Then
        Assertions.assertNotNull(output);
        Assertions.assertNotNull(output.getUri());
        Assertions.assertEquals(2, output.getDroppedItemsTotal());
        Assertions.assertEquals(4, output.getProcessedItemsTotal());
        assertFile(runContext, output, List.of(new KeyValue("k2", 2), new KeyValue("k4", 4)), KeyValue.class);
    }

    @Test
    void shouldFilterBinaryIonGivenValidBooleanExpressionForExclude() throws Exception {
        // Given
        RunContext runContext = runContextFactory.of();

        FilterItems task = FilterItems
            .builder()
            .from(Property.ofValue(generateBinaryKeyValueFile(TEST_VALID_ITEMS, runContext).toString()))
            .filterCondition(" {{ value % 2 == 0 }} ")
            .filterType(Property.ofValue(FilterItems.FilterType.EXCLUDE))
            .build();

        // When
        FilterItems.Output output = task.run(runContext);

        // Then
        Assertions.assertNotNull(output);
        Assertions.assertNotNull(output.getUri());
        Assertions.assertEquals(2, output.getDroppedItemsTotal());
        Assertions.assertEquals(4, output.getProcessedItemsTotal());
        assertFile(runContext, output, List.of(new KeyValue("k1", 1), new KeyValue("k3", 3)), KeyValue.class);
    }

    record KeyValue(String key, Object value) {
    }
}