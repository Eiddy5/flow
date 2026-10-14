package org.flow.core.models.flows;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlowIdTest {

    @Test
    void shouldGetUidWithoutRevision() {
        String id = FlowId.uidWithoutRevision("tenant", "org.flow.unittest", "flow-id");
        assertThat(id).isEqualTo("tenant_org.flow.unittest_flow-id");
    }

    @Test
    void shouldGetUidGivenEmptyRevision() {
        String id = FlowId.uid("tenant", "org.flow.unittest", "flow-id", Optional.empty());
        assertThat(id).isEqualTo("tenant_org.flow.unittest_flow-id_-1");
    }

    @Test
    void shouldGetUidGivenRevision() {
        String id = FlowId.uid("tenant", "org.flow.unittest", "flow-id", Optional.of(42));
        assertThat(id).isEqualTo("tenant_org.flow.unittest_flow-id_42");
    }
}