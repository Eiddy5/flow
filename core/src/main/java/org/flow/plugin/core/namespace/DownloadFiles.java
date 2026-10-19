package org.flow.plugin.core.namespace;

import java.io.InputStream;
import java.net.URI;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;

import org.flow.plugin.api.annotations.Example;
import org.flow.plugin.api.annotations.Plugin;
import org.flow.plugin.api.annotations.PluginProperty;
import org.flow.core.models.executions.metrics.Counter;
import org.flow.core.models.property.Property;
import org.flow.core.models.tasks.RunnableTask;
import org.flow.core.models.tasks.Task;
import org.flow.core.runners.RunContext;
import org.flow.core.storages.Namespace;
import org.flow.core.utils.PathMatcherPredicate;
import org.flow.core.utils.Rethrow;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;

import static org.flow.core.storages.NamespaceFile.toLogicalPath;

@Slf4j
@SuperBuilder
@Getter
@NoArgsConstructor
@Schema(
    title = "Download files from Namespace storage.",
    description = """
        Fetches files from a Namespace using file paths or glob patterns, storing them into the current working storage with preserved relative paths. Useful for sharing assets across Namespaces.

        `files` accepts a string or list (globs allowed); `destination` prefixes the output location."""
)
@Plugin(
    examples = {
        @Example(
            title = "Download a namespace file.",
            full = true,
            code = {
                """
                    id: download_file
                    namespace: company.team
                    tasks:
                      - id: download
                        type: org.flow.plugin.core.namespace.DownloadFiles
                        namespace: tutorial
                        files:
                          - "**input.txt"
                    """
            }
        ),
        @Example(
            title = "Download all namespace files from a specific namespace.",
            full = true,
            code = {
                """
                    id: download_all_files
                    namespace: company.team
                    tasks:
                      - id: download
                        type: org.flow.plugin.core.namespace.DownloadFiles
                        namespace: tutorial
                        files:
                          - "**"
                    """
            }
        )
    }
)
public class DownloadFiles extends Task implements RunnableTask<DownloadFiles.Output> {
    @NotNull
    @Schema(
        title = "The namespace from which you want to download files"
    )
    private Property<String> namespace;

    @NotNull
    @Schema(
        title = "A file or a list of files from the given namespace",
        description = "String or a list of strings; each string can either be a regex glob pattern or a file path URI.",
        anyOf = { List.class, String.class }
    )
    @PluginProperty(dynamic = true)
    private Object files;

    @Schema(
        title = "The folder where the downloaded files will be stored"
    )
    @Builder.Default
    private Property<String> destination = Property.ofValue("");

    @Override
    @SuppressWarnings("unchecked")
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();
        String renderedNamespace = runContext.render(this.namespace).as(String.class).orElseThrow();
        String renderedDestination = runContext.render(destination).as(String.class).orElseThrow();

        final Namespace namespace = runContext.storage().namespace(renderedNamespace);

        List<String> renderedFiles;
        if (files instanceof String filesString) {
            renderedFiles = List.of(runContext.render(filesString));
        } else if (files instanceof List<?> filesList) {
            renderedFiles = runContext.render((List<String>) filesList);
        } else {
            throw new IllegalArgumentException("The files property must be a string or a list of strings.");
        }

        Map<String, URI> downloaded = namespace.findAllFilesMatching(PathMatcherPredicate.matches(renderedFiles))
            .stream()
            .map(Rethrow.throwFunction(file ->
            {
                try (InputStream is = namespace.getFileContent(file)) {
                    URI uri = runContext.storage().putFile(is, renderedDestination + file.path());
                    logger.debug(String.format("Downloaded %s", uri));
                    return new AbstractMap.SimpleEntry<>(toLogicalPath(file.filePath()), uri);
                }
            }))
            .collect(Collectors.toMap(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue));
        runContext.metric(Counter.of("downloaded", downloaded.size()));
        return Output.builder().files(downloaded).build();
    }

    @Builder
    @Getter
    public static class Output implements org.flow.core.models.tasks.Output {
        @Schema(
            title = "Downloaded files",
            description = "The task returns a map containing the file path as a key and the file URI as a value."
        )
        private final Map<String, URI> files;
    }

}
