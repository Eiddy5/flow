package org.flow.core.services;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.runners.KVMetadataStateStore;
import org.flow.core.storages.StorageInterface;
import org.flow.core.storages.kv.*;

import io.micronaut.test.annotation.MockBean;
import jakarta.inject.Inject;

import static org.flow.core.tenant.TenantService.MAIN_TENANT;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@FlowTest
class KVStoreServiceTest {
    private static final String TEST_EXISTING_NAMESPACE = "org.flow.unittest";

    @Inject
    KVMetadataStateStore kvMetadataStateStore;

    @Inject
    KVStoreService storeService;

    @Inject
    StorageInterface storageInterface;

    @Test
    void shouldGetKVStoreForExistingNamespaceGivenFromNull() {
        Assertions.assertNotNull(storeService.get(MAIN_TENANT, TEST_EXISTING_NAMESPACE, null));
    }

    @Test
    void shouldThrowExceptionWhenAccessingKVStoreForNonExistingNamespace() {
        KVStoreException exception = Assertions.assertThrows(KVStoreException.class, () -> storeService.get(MAIN_TENANT, "org.flow.unittest.unknown", null));
        Assertions.assertTrue(exception.getMessage().contains("namespace 'org.flow.unittest.unknown' does not exist"));
    }

    @Test
    void shouldGetKVStoreForAnyNamespaceWhenAccessingFromChildNamespace() {
        Assertions.assertNotNull(storeService.get(MAIN_TENANT, "org.flow", TEST_EXISTING_NAMESPACE));
    }

    /**
     * Regression test for issue #10244 — "development" shares the "dev" string prefix but is not one
     * of its dot-delimited descendants, so it must not be treated as a child namespace (which would
     * skip both the cross-namespace ACL check and the namespace-existence check).
     */
    @Test
    void shouldNotTreatSiblingNamespaceSharingPrefixAsChild() {
        // Before the fix, "development".startsWith("dev") made this silently succeed with no ACL or
        // existence check at all. After the fix, "dev" is correctly treated as unrelated to
        // "development" and is rejected — as a real ACL denial or, absent one, a not-found namespace.
        Assertions.assertThrows(
            KVStoreException.class, () -> storeService.get(MAIN_TENANT, "dev", "development")
        );
    }

    @Test
    void shouldDenyKVStoreAccessWhenAccessingFromPrefixSiblingNamespace() {
        assertThatThrownBy(() -> storeService.get(MAIN_TENANT, "prod", "prod2"))
            .isInstanceOf(KVStoreException.class)
            .hasMessageContaining("Access to 'prod' namespace is not allowed from 'prod2'");
    }

    @Test
    void shouldGetKVStoreFromNonExistingNamespaceWithAKV() throws IOException {
        KVStore kvStore = new InternalKVStore(MAIN_TENANT, "system", storageInterface, kvMetadataStateStore);
        kvStore.put("key", new KVValueAndMetadata(new KVMetadata("myDescription", Duration.ofHours(1)), "value"));
        Assertions.assertNotNull(storeService.get(MAIN_TENANT, "system", null));
    }

    @MockBean(NamespaceService.class)
    public static class MockNamespaceService extends DefaultNamespaceService {

        public MockNamespaceService() {
            super(null);
        }

        @Override
        public boolean isNamespaceExists(String tenant, String namespace) {
            return namespace.equals(TEST_EXISTING_NAMESPACE);
        }

        /** Emulates an EE allow-list where every namespace allows only itself, which OSS never denies. */
        @Override
        public boolean isAllowedNamespace(String tenant, String namespace, String fromTenant, String fromNamespace) {
            return namespace.equals(fromNamespace);
        }
    }
}
