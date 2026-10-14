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
import org.flow.core.storages.StorageInterface;
import org.flow.core.utils.IdUtils;
import org.flow.core.utils.TestsUtils;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
public class DownloadFilesTest {
    @Inject
    StorageInterface storageInterface;

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void shouldDownloadNamespaceFile() throws Exception {
        String namespaceId = "org.flow." + IdUtils.create();
        DownloadFiles downloadFiles = DownloadFiles.builder()
            .id(DownloadFiles.class.getSimpleName())
            .type(DownloadFiles.class.getName())
            .files(List.of("**test1.txt"))
            .namespace(Property.ofExpression("{{ inputs.namespace }}"))
            .build();

        final RunContext runContext = TestsUtils.mockRunContext(this.runContextFactory, downloadFiles, Map.of("namespace", namespaceId));
        final Namespace namespace = runContext.storage().namespace(namespaceId);

        namespace.putFile(Path.of("/a/b/test1.txt"), new ByteArrayInputStream("1".getBytes(StandardCharsets.UTF_8)));
        namespace.putFile(Path.of("/a/b/test2.txt"), new ByteArrayInputStream("2".getBytes(StandardCharsets.UTF_8)));

        DownloadFiles.Output output = downloadFiles.run(runContext);

        assertThat(output.getFiles().size()).isEqualTo(1);
        assertThat(output.getFiles().get("/a/b/test1.txt")).isNotNull();

    }

    @Test
    void shouldDownloadNestedDotfileNamespaceFile() throws Exception {
        String namespaceId = "org.flow." + IdUtils.create();
        DownloadFiles downloadFiles = DownloadFiles.builder()
            .id(DownloadFiles.class.getSimpleName())
            .type(DownloadFiles.class.getName())
            .files(List.of(".env"))
            .namespace(Property.ofExpression("{{ inputs.namespace }}"))
            .build();

        final RunContext runContext = TestsUtils.mockRunContext(this.runContextFactory, downloadFiles, Map.of("namespace", namespaceId));
        final Namespace namespace = runContext.storage().namespace(namespaceId);

        namespace.putFile(Path.of("/a/b/.env"), new ByteArrayInputStream("1".getBytes(StandardCharsets.UTF_8)));

        DownloadFiles.Output output = downloadFiles.run(runContext);

        assertThat(output.getFiles().size()).isEqualTo(1);
        assertThat(output.getFiles().get("/a/b/.env")).isNotNull();

    }
}
