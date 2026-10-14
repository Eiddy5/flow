package org.flow.core.junit.extensions;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.junit.annotations.LoadFlows;
import org.flow.core.models.flows.FlowInterface;
import org.flow.core.repositories.FlowRepositoryInterface;

import jakarta.inject.Inject;

import static org.flow.core.tenant.TenantService.MAIN_TENANT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards {@link AbstractLoaderExtension} against the directory regression: {@code @LoadFlows} must
 * accept a directory path and load every flow it contains (not just single files). Without the fix,
 * the metastore-wait loop tries to parse the directory itself as a flow and fails.
 */
@FlowTest
class FlowLoaderDirectoryTest {

    @Inject
    private FlowRepositoryInterface flowRepository;

    @Test
    @LoadFlows({ "flows/loaddir" })
    void shouldLoadEveryFlowInDirectory() {
        List<String> ids = flowRepository.findAllForAllTenants().stream()
            .filter(flow -> MAIN_TENANT.equals(flow.getTenantId()))
            .filter(flow -> "org.flow.tests.loaddir".equals(flow.getNamespace()))
            .map(FlowInterface::getId)
            .toList();

        assertThat(ids).containsExactlyInAnyOrder("load-directory-first", "load-directory-second");
    }
}
