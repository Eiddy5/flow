package org.flow.core.storages.kv;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import org.flow.core.annotations.RequiresExecutor;
import org.flow.core.models.FetchVersion;
import org.flow.core.models.QueryFilter;
import org.flow.core.models.QueryFilter.Field;
import org.flow.core.models.QueryFilter.Op;
import org.flow.core.repositories.FlowRepositoryInterface;
import org.flow.core.repositories.KvMetadataRepositoryInterface;
import org.flow.core.services.KVService;
import org.flow.core.storages.kv.configuration.KVPurgeConfiguration;
import org.flow.core.tenant.TenantService;

import io.micronaut.context.annotation.Requires;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.Sort;
import io.micronaut.data.model.Sort.Order;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Requires(property = "flow.kv.purge-expired.enabled", value = "true", defaultValue = "true")
@RequiresExecutor
@Singleton
public class KVPurgeCleaner {

    @Inject
    private KVService kvService;

    @Inject
    private FlowRepositoryInterface flowRepository;

    @Inject
    private KvMetadataRepositoryInterface kvMetadataRepository;

    @Inject
    private KVPurgeConfiguration kvPurgeConfiguration;

    @Scheduled(initialDelay = "${flow.kv.purge-expired.initial-delay:PT1H}", fixedDelay = "${flow.kv.purge-expired.fixed-delay:PT1H}")
    public void purgeExpired() {
        log.info("Start cleaning expired KV store entries");
        List<String> tenants = findTenants();
        for (String tenant : tenants) {
            purgeKVEntriesForTenant(tenant);
        }
    }

    private void purgeKVEntriesForTenant(String tenant) {
        List<String> namespaces = findNamespaces(tenant);
        Instant now = Instant.now();
        for (String namespace : namespaces) {
            try {
                List<KVEntry> expiredEntries;
                do {
                    expiredEntries = kvMetadataRepository.find(
                        //We always fetch the first page because we delete every KV entries that we find.
                        Pageable.from(1, kvPurgeConfiguration.batchSize(), Sort.of(Order.asc("name"))),
                        tenant,
                        List.of(
                            QueryFilter.builder().field(Field.NAMESPACE).value(namespace).operation(Op.EQUALS).build(),
                            QueryFilter.builder().field(Field.EXPIRATION_DATE).value(now).operation(Op.LESS_THAN).build()
                        ),
                        false,
                        true,
                        FetchVersion.ALL
                    ).stream()
                        .map(KVEntry::from)
                        .toList();
                    if (!expiredEntries.isEmpty()) {
                        kvService.purge(tenant, namespace, expiredEntries);
                        log.info(
                            "{} KV store entries have been deleted on the namespace {} on tenant {}",
                            expiredEntries.size(), namespace, tenant
                        );
                    }
                } while (!expiredEntries.isEmpty());
            } catch (IOException e) {
                log.error("Unable to delete KV entries for the namespace {} on tenant {}", namespace, tenant, e);
            }
        }
    }

    protected List<String> findNamespaces(String tenant) {
        return flowRepository.findDistinctNamespace(tenant);
    }

    protected List<String> findTenants() {
        return List.of(TenantService.MAIN_TENANT);
    }
}
