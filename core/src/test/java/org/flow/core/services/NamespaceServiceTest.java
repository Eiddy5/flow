package org.flow.core.services;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.flows.Flow;
import org.flow.core.models.flows.GenericFlow;
import org.flow.core.repositories.FlowRepositoryInterface;
import org.flow.core.tenant.TenantService;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

@FlowTest
class NamespaceServiceTest {
    @Inject
    private FlowRepositoryInterface flowRepository;

    @Inject
    private NamespaceService namespaceService;

    @Test
    void isNamespaceExists() {
        Flow flow = Flow.builder().id("test").namespace("org.flow").tenantId(TenantService.MAIN_TENANT).build();
        flowRepository.create(GenericFlow.of(flow));

        assertThat(namespaceService.isNamespaceExists(TenantService.MAIN_TENANT, "org.flow")).isTrue();
    }

    @Test
    void isNamespaceExistsShouldReturnFalseWhenNotFond() {
        assertThat(namespaceService.isNamespaceExists(TenantService.MAIN_TENANT, "notFound")).isFalse();
    }

    @Test
    void isNamespaceAllowed() {
        assertThat(namespaceService.requireExistingNamespace(TenantService.MAIN_TENANT, "org.flow")).isFalse();
    }

    @Test
    void isAllowedNamespace() {
        assertTrue(namespaceService.isAllowedNamespace("tenant", "namespace", "fromTenant", "fromNamespace"));
    }

    @Test
    void checkAllowedNamespace() {
        assertDoesNotThrow(() -> namespaceService.checkAllowedNamespace("tenant", "namespace", "fromTenant", "fromNamespace"));
    }

    @Test
    void areAllowedAllNamespaces() {
        assertTrue(namespaceService.areAllowedAllNamespaces("tenant", "fromTenant", "fromNamespace"));
    }

    @Test
    void checkAllowedAllNamespaces() {
        assertDoesNotThrow(() -> namespaceService.checkAllowedAllNamespaces("tenant", "fromTenant", "fromNamespace"));
    }

}