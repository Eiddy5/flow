package org.flow.jdbc.repository;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.flow.core.models.flows.FlowWithSource;
import org.flow.core.models.topologies.FlowTopology;
import org.flow.core.repositories.AbstractFlowTopologyRepositoryTest;
import org.flow.core.utils.TestsUtils;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class AbstractJdbcFlowTopologyRepositoryTest extends AbstractFlowTopologyRepositoryTest {
    @Inject
    private AbstractJdbcFlowTopologyRepository flowTopologyRepository;

    @Test
    void saveMultiple() {
        String tenant = TestsUtils.randomTenant(this.getClass().getSimpleName());
        FlowWithSource flow = FlowWithSource.builder()
            .tenantId(tenant)
            .id("flow-a")
            .namespace("org.flow.tests")
            .revision(1)
            .build();

        flowTopologyRepository.save(
            flow,
            List.of(
                createSimpleFlowTopology(tenant, "flow-a", "flow-b", "org.flow.tests")
            )
        );

        List<FlowTopology> list = flowTopologyRepository.findByFlow(tenant, "org.flow.tests", "flow-a", false);
        assertThat(list.size()).isEqualTo(1);

        flowTopologyRepository.save(
            flow,
            List.of(
                createSimpleFlowTopology(tenant, "flow-a", "flow-b", "org.flow.tests"),
                createSimpleFlowTopology(tenant, "flow-a", "flow-c", "org.flow.tests")
            )
        );

        list = flowTopologyRepository.findByNamespace(tenant, "org.flow.tests");

        assertThat(list.size()).isEqualTo(2);
    }
}