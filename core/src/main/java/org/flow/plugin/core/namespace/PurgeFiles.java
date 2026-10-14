package org.flow.plugin.core.namespace;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.flow.core.models.annotations.Example;
import org.flow.core.models.annotations.Plugin;
import org.flow.core.models.property.Property;
import org.flow.core.models.tasks.RunnableTask;
import org.flow.core.models.tasks.Task;
import org.flow.core.namespace.NamespaceFileService;
import org.flow.core.runners.DefaultRunContext;
import org.flow.core.runners.RunContext;
import org.flow.core.storages.NamespaceFile;
import org.flow.plugin.core.purge.PurgeTask;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Schema(
    title = "Purge Namespace files (and versions).",
    description = """
        Deletes files from Namespace storage using a purge `behavior` (default keeps 1 latest version), optional file glob, and Namespace filters (`namespaces` list or `namespacePattern`). Child Namespaces are included by default.

        Use to clean old asset versions at scale; behavior controls retention (keepAmount/before)."""
)
@Plugin(
    examples = {
        @Example(
            title = "Purge old versions of namespace files for a namespace tree.",
            full = true,
            code = """
                id: purge_namespace_files
                namespace: system

                tasks:
                  - id: purge_files
                    type: org.flow.plugin.core.namespace.PurgeFiles
                    namespaces:
                      - company
                    includeChildNamespaces: true
                    filePattern: "**/*.sql"
                    behavior:
                      type: version
                      before: "2025-01-01T00:00:00Z"
                """
        )
    }
)
public class PurgeFiles extends Task implements PurgeTask<NamespaceFile>, RunnableTask<PurgeFiles.Output> {
    @Schema(
        title = "File pattern, e.g. 'AI_*'",
        description = "Delete only files whose path is matching the glob pattern."
    )
    private Property<String> filePattern;

    @Schema(
        title = "List of namespaces to delete files from",
        description = "If not set, all namespaces will be considered. Can't be used with `namespacePattern` - use one or the other."
    )
    private Property<List<String>> namespaces;

    @Schema(
        title = "Glob pattern for the namespaces to delete files from",
        description = "If not set (e.g., AI_*), all namespaces will be considered. Can't be used with `namespaces` - use one or the other."
    )
    private Property<String> namespacePattern;

    @Schema(
        title = "Purge behavior",
        description = "Defines how files are purged."
    )
    @Builder.Default
    @Valid
    private Property<FilesPurgeBehavior> behavior = Property.ofValue(Version.builder().keepAmount(1).build());

    @Schema(
        title = "Delete files from child namespaces",
        description = "Defaults to true. This means that if you set `namespaces` to `company`, it will also delete files from `company.team`, `company.data`, etc."
    )
    @Builder.Default
    private Property<Boolean> includeChildNamespaces = Property.ofValue(true);

    @Override
    public Output run(RunContext runContext) throws Exception {
        List<String> filesNamespaces = findNamespaces(runContext);
        runContext.logger().info("purging {} namespaces: {}", filesNamespaces.size(), filesNamespaces);
        AtomicLong count = new AtomicLong();
        FilesPurgeBehavior renderedBehavior = runContext.render(behavior).as(FilesPurgeBehavior.class).orElseThrow();
        String tenantId = runContext.flowInfo().tenantId();
        NamespaceFileService namespaceFileService = ((DefaultRunContext) runContext).services().additionalService(NamespaceFileService.class);
        for (String ns : filesNamespaces) {
            List<NamespaceFile> toPurge = filterItems(runContext, renderedBehavior.entriesToPurge(tenantId, ns, namespaceFileService));
            count.addAndGet(namespaceFileService.purge(tenantId, ns, toPurge));
        }
        runContext.logger().info("purged {} files", count.get());

        return Output.builder()
            .size(count.get())
            .build();
    }

    @Override
    public Property<String> filterPattern() {
        return filePattern;
    }

    @Override
    public String filterTargetExtractor(NamespaceFile item) {
        return item.path();
    }

    @Builder
    @Getter
    public static class Output implements org.flow.core.models.tasks.Output {
        @Schema(
            title = "The number of purged namespace file versions"
        )
        private Long size;
    }
}
