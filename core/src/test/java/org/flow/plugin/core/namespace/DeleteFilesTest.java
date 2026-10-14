package org.flow.plugin.core.namespace;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.property.Property;
import org.flow.core.runners.RunContext;
import org.flow.core.runners.RunContextFactory;
import org.flow.core.storages.Namespace;
import org.flow.core.utils.IdUtils;
import org.flow.core.utils.TestsUtils;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
class DeleteFilesTest {
    @Inject
    RunContextFactory runContextFactory;

    @Test
    void shouldDeleteNamespaceFilesForMatchingExpression() throws Exception {
        // Given
        String namespaceId = "org.flow." + IdUtils.create();

        DeleteFiles deleteFiles = DeleteFiles.builder()
            .id(DeleteFiles.class.getSimpleName())
            .type(DeleteFiles.class.getName())
            .files(List.of("**test1*"))
            .namespace(Property.ofExpression("{{ inputs.namespace }}"))
            .build();

        final RunContext runContext = TestsUtils.mockRunContext(this.runContextFactory, deleteFiles, Map.of("namespace", namespaceId));
        final Namespace namespace = runContext.storage().namespace(namespaceId);

        namespace.putFile(Path.of("/a/b/test1.txt"), new ByteArrayInputStream("1".getBytes(StandardCharsets.UTF_8)));
        namespace.putFile(Path.of("/a/b/test2.txt"), new ByteArrayInputStream("2".getBytes(StandardCharsets.UTF_8)));

        assertThat(namespace.all("/a/b/", false).size()).isEqualTo(2);

        // When
        assertThat(deleteFiles.run(runContext)).isNotNull();

        // Then
        assertThat(namespace.all("/a/b/", false).size()).isEqualTo(1);
    }

    @Test
    void shouldDeleteParentFolder() throws Exception {
        // Given
        String namespaceId = "org.flow." + IdUtils.create();

        DeleteFiles deleteFiles = DeleteFiles.builder()
            .id(DeleteFiles.class.getSimpleName())
            .type(DeleteFiles.class.getName())
            .files(List.of("**/file.txt"))
            .namespace(Property.ofExpression("{{ inputs.namespace }}"))
            .deleteParentFolder(Property.ofValue(true))
            .build();

        final RunContext runContext = TestsUtils.mockRunContext(this.runContextFactory, deleteFiles, Map.of("namespace", namespaceId));
        final Namespace namespace = runContext.storage().namespace(namespaceId);

        namespace.putFile(Path.of("/folder/file.txt"), new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8)));

        assertThat(namespace.all("/folder/", false).size()).isEqualTo(1);

        // When
        assertThat(deleteFiles.run(runContext)).isNotNull();

        // Then
        assertThat(namespace.all("/folder/", false).size()).isZero();
        assertThat(namespace.all("/", false).size()).isZero();
    }

    @Test
    void shouldNotDeleteParentFolderWhenFlagIsFalse() throws Exception {
        // Given
        String namespaceId = "org.flow." + IdUtils.create();

        DeleteFiles deleteFiles = DeleteFiles.builder()
            .id(DeleteFiles.class.getSimpleName())
            .type(DeleteFiles.class.getName())
            .files(List.of("**/file.txt"))
            .namespace(Property.ofExpression("{{ inputs.namespace }}"))
            .deleteParentFolder(Property.ofValue(false))
            .build();

        final RunContext runContext = TestsUtils.mockRunContext(this.runContextFactory, deleteFiles, Map.of("namespace", namespaceId));
        final Namespace namespace = runContext.storage().namespace(namespaceId);

        namespace.putFile(Path.of("/folder/file.txt"), new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8)));

        assertThat(namespace.all("/folder/", false).size()).isEqualTo(1);

        // When
        assertThat(deleteFiles.run(runContext)).isNotNull();

        // Then
        assertThat(namespace.all("/folder/", false).size()).isZero();
        assertThat(namespace.all("/", true).size()).isEqualTo(1); // Folder should still exist
    }

    @Test
    void shouldNotDeleteParentFolderWhenMultipleFilesExist() throws Exception {
        // Given
        String namespaceId = "org.flow." + IdUtils.create();

        DeleteFiles deleteFiles = DeleteFiles.builder()
            .id(DeleteFiles.class.getSimpleName())
            .type(DeleteFiles.class.getName())
            .files(List.of("**/file1.txt"))
            .namespace(Property.ofExpression("{{ inputs.namespace }}"))
            .deleteParentFolder(Property.ofValue(true))
            .build();

        final RunContext runContext = TestsUtils.mockRunContext(this.runContextFactory, deleteFiles, Map.of("namespace", namespaceId));
        final Namespace namespace = runContext.storage().namespace(namespaceId);

        namespace.putFile(Path.of("/folder/file1.txt"), new ByteArrayInputStream("content1".getBytes(StandardCharsets.UTF_8)));
        namespace.putFile(Path.of("/folder/file2.txt"), new ByteArrayInputStream("content2".getBytes(StandardCharsets.UTF_8)));

        assertThat(namespace.all("/folder/", false).size()).isEqualTo(2);

        // When
        assertThat(deleteFiles.run(runContext)).isNotNull();

        // Then
        assertThat(namespace.all("/folder/", false).size()).isEqualTo(1); // One file should still exist
        assertThat(namespace.all("/", false).size()).isEqualTo(1); // Folder should still exist
    }
}
