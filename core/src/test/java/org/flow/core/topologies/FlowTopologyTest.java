package org.flow.core.topologies;

import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import org.flow.core.exceptions.FlowProcessingException;
import org.flow.core.models.flows.FlowWithSource;
import org.flow.core.models.topologies.FlowNode;
import org.flow.core.models.topologies.FlowTopology;
import org.flow.core.models.topologies.FlowTopologyGraph;
import org.flow.core.repositories.FlowTopologyRepositoryInterface;
import org.flow.core.services.FlowService;
import org.flow.core.utils.TestsUtils;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@MicronautTest
public class FlowTopologyTest {
    @Inject
    private FlowService flowService;
    @Inject
    private FlowTopologyService flowTopologyService;
    @Inject
    private FlowTopologyRepositoryInterface flowTopologyRepository;

    @Test
    void should_findDependencies_simpleCase() throws FlowProcessingException {
        // Given
        var tenantId = TestsUtils.randomTenant();
        var child = flowService.importFlow(
            tenantId,
            """
                id: child
                namespace: org.flow.unittest
                tasks:
                  - id: debug
                    type: org.flow.plugin.core.debug.Return
                    format: "child"
                """
        );
        var parent = flowService.importFlow(tenantId, """
            id: parent
            namespace: org.flow.unittest
            tasks:
              - id: subflow
                type: org.flow.plugin.core.flow.Subflow
                flowId: child
                namespace: org.flow.unittest
            """);
        var unrelatedFlow = flowService.importFlow(tenantId, """
            id: unrelated_flow
            namespace: org.flow.unittest
            tasks:
              - id: debug
                type: org.flow.plugin.core.debug.Return
                format: "unrelated"
            """);

        // When
        computeAndSaveTopologies(List.of(child, parent, unrelatedFlow));

        var dependencies = flowService.findDependencies(tenantId, "org.flow.unittest", parent.getId(), false, true);

        // Then
        assertThat(dependencies.map(FlowTopologyTestData::of))
            .containsExactlyInAnyOrder(
                new FlowTopologyTestData(parent, child)
            );
    }

    @Test
    void should_findDependencies_subchildAndSuperParent() throws FlowProcessingException {
        // Given
        var tenantId = TestsUtils.randomTenant();
        var subChild = flowService.importFlow(
            tenantId,
            """
                id: sub_child
                namespace: org.flow.unittest
                tasks:
                  - id: debug
                    type: org.flow.plugin.core.debug.Return
                    format: "debug"
                """
        );
        var child = flowService.importFlow(
            tenantId,
            """
                id: child
                namespace: org.flow.unittest
                tasks:
                  - id: subflow
                    type: org.flow.plugin.core.flow.Subflow
                    flowId: sub_child
                    namespace: org.flow.unittest
                """
        );
        var superParent = flowService.importFlow(tenantId, """
            id: super_parent
            namespace: org.flow.unittest
            tasks:
              - id: subflow
                type: org.flow.plugin.core.flow.Subflow
                flowId: parent
                namespace: org.flow.unittest
            """);
        var parent = flowService.importFlow(tenantId, """
            id: parent
            namespace: org.flow.unittest
            tasks:
              - id: subflow
                type: org.flow.plugin.core.flow.Subflow
                flowId: child
                namespace: org.flow.unittest
            """);
        var unrelatedFlow = flowService.importFlow(tenantId, """
            id: unrelated_flow
            namespace: org.flow.unittest
            tasks:
              - id: debug
                type: org.flow.plugin.core.debug.Return
                format: "debug"
            """);

        // When
        computeAndSaveTopologies(List.of(subChild, child, superParent, parent, unrelatedFlow));

        var dependencies = flowService.findDependencies(tenantId, "org.flow.unittest", parent.getId(), false, true);

        // Then
        assertThat(dependencies.map(FlowTopologyTestData::of))
            .containsExactlyInAnyOrder(
                new FlowTopologyTestData(superParent, parent),
                new FlowTopologyTestData(parent, child),
                new FlowTopologyTestData(child, subChild)
            );
    }

    @Test
    void should_findDependencies_cyclicTriggers() throws FlowProcessingException {
        // Given
        var tenantId = TestsUtils.randomTenant();
        var triggeredFlowOne = flowService.importFlow(
            tenantId,
            """
                id: triggered_flow_one
                namespace: org.flow.unittest
                tasks:
                    - id: debug
                      type: org.flow.plugin.core.debug.Return
                      format: "debug"
                triggers:
                  - id: listen
                    type: org.flow.plugin.core.trigger.Flow
                    dependsOn:
                      - states:
                          - FAILED
                """
        );
        var triggeredFlowTwo = flowService.importFlow(tenantId, """
            id: triggered_flow_two
            namespace: org.flow.unittest
            tasks:
              - id: debug
                type: org.flow.plugin.core.debug.Return
                format: "debug"
            triggers:
              - id: listen
                type: org.flow.plugin.core.trigger.Flow
                dependsOn:
                  - states:
                      - FAILED
            """);

        // When
        computeAndSaveTopologies(List.of(triggeredFlowOne, triggeredFlowTwo));

        var dependencies = flowService.findDependencies(tenantId, "org.flow.unittest", triggeredFlowTwo.getId(), false, true).toList();

        // Then
        assertThat(dependencies.stream().map(FlowTopologyTestData::of))
            .containsExactlyInAnyOrder(
                new FlowTopologyTestData(triggeredFlowTwo, triggeredFlowOne),
                new FlowTopologyTestData(triggeredFlowOne, triggeredFlowTwo)
            );

    }

    @Test
    void flowTriggerWithTargetFlow() throws FlowProcessingException {
        // Given
        var tenantId = TestsUtils.randomTenant();
        var parent = flowService.importFlow(
            tenantId,
            """
                id: parent
                namespace: org.flow.unittest
                inputs:
                  - id: a
                    type: BOOL
                    defaults: true

                  - id: b
                    type: BOOL
                    defaults: "{{ inputs.a == true }}"
                    dependsOn:
                      inputs:
                        - a
                tasks:
                  - id: helloA
                    type: org.flow.plugin.core.log.Log
                    message: Hello A
                """
        );
        var child = flowService.importFlow(tenantId, """
            id: child
            namespace: org.flow.unittest
            tasks:
              - id: helloB
                type: org.flow.plugin.core.log.Log
                message: Hello B
            triggers:
              - id: release
                type: org.flow.plugin.core.trigger.Flow
                states:
                  - SUCCESS
                dependsOn:
                  - namespace: org.flow.unittest
                    flowId: parent
            """);
        var unrelatedFlow = flowService.importFlow(tenantId, """
            id: unrelated_flow
            namespace: org.flow.unittest
            tasks:
              - id: debug
                type: org.flow.plugin.core.debug.Return
                format: "debug"
            """);

        // When
        computeAndSaveTopologies(List.of(child, parent, unrelatedFlow));

        var dependencies = flowService.findDependencies(tenantId, "org.flow.unittest", parent.getId(), false, true);

        // Then
        assertThat(dependencies.map(FlowTopologyTestData::of))
            .containsExactlyInAnyOrder(
                new FlowTopologyTestData(parent, child)
            );
    }

    @Test
    void testNamespaceGraph() throws FlowProcessingException {
        var tenantId = TestsUtils.randomTenant();

        var subChild = flowService.importFlow(
            tenantId,
            """
                id: sub_child
                namespace: org.flow.unittest.sub
                tasks:
                  - id: log
                    type: org.flow.plugin.core.log.Log
                    message: Sub Child
                """
        );

        var child = flowService.importFlow(
            tenantId,
            """
                id: child
                namespace: org.flow.unittest
                tasks:
                  - id: callSub
                    type: org.flow.plugin.core.flow.Subflow
                    flowId: sub_child
                    namespace: org.flow.unittest.sub
                """
        );

        var parent = flowService.importFlow(
            tenantId,
            """
                id: parent
                namespace: org.flow.unittest
                tasks:
                  - id: callChild
                    type: org.flow.plugin.core.flow.Subflow
                    flowId: child
                    namespace: org.flow.unittest
                """
        );

        var unrelated = flowService.importFlow(
            tenantId,
            """
                id: unrelated
                namespace: org.flow.unittest
                tasks:
                  - id: log
                    type: org.flow.plugin.core.log.Log
                    message: Not part of deps
                """
        );

        computeAndSaveTopologies(List.of(subChild, child, parent, unrelated));

        FlowTopologyGraph graph = flowTopologyService.namespaceGraph(tenantId, "org.flow.unittest");

        assertThat(graph.getNodes())
            .extracting(FlowNode::getId)
            .contains("parent", "child", "sub_child", "unrelated");

        assertThat(graph.getEdges().size()).isEqualTo(2);
    }

    /**
     * this function mimics the production behaviour
     */
    private void computeAndSaveTopologies(List<@NotNull FlowWithSource> flows) {
        flows.forEach(
            flow -> flowTopologyService
                .topology(
                    flow,
                    flows
                ).distinct()
                .forEach(topology -> flowTopologyRepository.save(topology))
        );
    }

    record FlowTopologyTestData(String sourceUid, String destinationUid) {
        public FlowTopologyTestData(FlowWithSource parent, FlowWithSource child) {
            this(parent.uidWithoutRevision(), child.uidWithoutRevision());
        }

        public static FlowTopologyTestData of(FlowTopology flowTopology) {
            return new FlowTopologyTestData(flowTopology.getSource().getUid(), flowTopology.getDestination().getUid());
        }

        @Override
        public String toString() {
            return sourceUid + " -> " + destinationUid;
        }
    }
}
