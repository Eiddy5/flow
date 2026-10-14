package org.flow.plugin.core.kv;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.flow.core.models.annotations.Example;
import org.flow.core.models.annotations.Plugin;
import org.flow.core.models.property.Property;
import org.flow.core.models.tasks.RunnableTask;
import org.flow.core.models.tasks.Task;
import org.flow.core.runners.DefaultRunContext;
import org.flow.core.runners.RunContext;
import org.flow.core.services.KVService;
import org.flow.core.services.KVStoreService;
import org.flow.core.storages.kv.KVEntry;
import org.flow.plugin.core.purge.PurgeTask;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
    title = "Purge keys from the KV store.",
    description = """
        Deletes keys across Namespaces using a purge `behavior` (default: expired-only). Filter by explicit `namespaces` or `namespacePattern`, optional `keyPattern`, and include/exclude child namespaces."""
)
@Plugin(
    examples = {
        @Example(
            title = "Delete expired keys globally for a specific namespace, with or without including child namespaces.",
            full = true,
            code = """
                id: purge_kv_store
                namespace: system

                tasks:
                  - id: purge_kv
                    type: org.flow.plugin.core.kv.PurgeKV
                    behavior:
                      type: key
                      expiredOnly: true
                    namespaces:
                      - company
                    includeChildNamespaces: true
                """
        )
    }
)
public class PurgeKV extends Task implements PurgeTask<KVEntry>, RunnableTask<PurgeKV.Output> {
    @Schema(
        title = "Key pattern, e.g. 'AI_*'",
        description = "Delete only keys matching the glob pattern."
    )
    private Property<String> keyPattern;

    @Schema(
        title = "List of namespaces to delete keys from",
        description = "If not set, all namespaces will be considered. Can't be used with `namespacePattern` - use one or the other."
    )
    private Property<List<String>> namespaces;

    @Schema(
        title = "Glob pattern for the namespaces to delete keys from",
        description = "If not set (e.g., AI_*), all namespaces will be considered. Can't be used with `namespaces` - use one or the other."
    )
    private Property<String> namespacePattern;

    @Schema(
        title = "Purge behavior",
        description = "Defines how keys are purged."
    )
    @Builder.Default
    @Valid
    @NotNull
    private Property<KvPurgeBehavior> behavior = Property.ofValue(Key.builder().expiredOnly(true).build());

    @Schema(
        title = "Delete keys from child namespaces",
        description = "Defaults to true. This means that if you set `namespaces` to `company`, it will also delete keys from `company.team`, `company.data`, etc."
    )
    @Builder.Default
    private Property<Boolean> includeChildNamespaces = Property.ofValue(true);

    @Override
    public Output run(RunContext runContext) throws Exception {
        List<String> kvNamespaces = findNamespaces(runContext);
        runContext.logger().info("purging {} namespaces: {}", kvNamespaces.size(), kvNamespaces);
        AtomicLong count = new AtomicLong();
        KvPurgeBehavior renderedBehavior = runContext.render(behavior).as(KvPurgeBehavior.class).orElseThrow();

        String tenantId = runContext.flowInfo().tenantId();
        String namespace = runContext.flowInfo().namespace();
        for (String targetNamespace : kvNamespaces) {
            KVStoreService kvStoreService = ((DefaultRunContext) runContext).services().additionalService(KVStoreService.class);
            KVService kvService = ((DefaultRunContext) runContext).services().additionalService(KVService.class);
            kvStoreService.checkAccessNamespaceIsAllowed(tenantId, targetNamespace, namespace);
            List<KVEntry> toPurge = filterItems(runContext, renderedBehavior.entriesToPurge(tenantId, targetNamespace, kvService));
            count.addAndGet(kvService.purge(tenantId, targetNamespace, toPurge));
        }
        runContext.logger().info("purged {} keys", count.get());

        return Output.builder()
            .size(count.get())
            .build();
    }

    @Override
    public Property<String> filterPattern() {
        return keyPattern;
    }

    @Override
    public String filterTargetExtractor(KVEntry item) {
        return item.key();
    }

    @Builder
    @Getter
    public static class Output implements org.flow.core.models.tasks.Output {
        @Schema(
            title = "The number of purged KV pairs"
        )
        private Long size;
    }
}
