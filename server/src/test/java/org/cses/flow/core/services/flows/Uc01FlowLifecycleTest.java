package org.cses.flow.core.services.flows;

import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowStatus;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.exceptions.shared.WorkflowException;
import org.cses.flow.core.services.executions.WorkflowUcFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UC: docs/uc/flow/UC-01 创建并发布 Flow.md
 */
class Uc01FlowLifecycleTest {

    private final List<WorkflowUcFixture> fixtures = new ArrayList<>();

    @AfterEach
    void closeFixtures() {
        fixtures.forEach(WorkflowUcFixture::close);
    }

    @Test
    void s1CreatesAndEditsDraftWithStableIdentity() {
        Fixture fixture = fixture();
        Flow saved = fixture.service.saveDraft(
            fixture.session,
            initialYaml("uc01-s1-flow")
        );

        fixture.service.saveDraft(
            fixture.session,
            saved.id(),
            modifiedYaml("uc01-s1-flow")
        );
        Flow reopened = flow(
            fixture,
            saved.id(),
            null,
            FlowStatus.DRAFT
        ).orElseThrow();

        // PASS-S1-01
        assertFalse(saved.id().isBlank());
        assertNull(saved.reversion());
        // PASS-S1-02
        assertEquals(saved.id(), reopened.id());
        assertNull(reopened.reversion());
        // PASS-S1-03
        assertEquals(
            "已修改 Flow",
            reopened.description()
        );
        assertAutomaticTasks(reopened, "start", "finish");
        assertTrue(flow(
            fixture,
            saved.id(),
            1L,
            FlowStatus.DEPLOYED
        ).isEmpty());
    }

    @Test
    void s2PublishesUpgradesAndClosesFlow() {
        Fixture fixture = fixture();
        String key = "uc01-s2-flow";
        String id = fixture.service.saveDraft(
            fixture.session,
            initialYaml(key)
        ).id();
        Flow version1 = fixture.service.publish(
            fixture.session,
            id
        );

        // PASS-S2-01
        assertEquals(FlowStatus.DEPLOYED, version1.status());
        assertEquals(1L, version1.reversion());
        assertTrue(flow(
            fixture,
            id,
            null,
            FlowStatus.DRAFT
        ).isEmpty());

        fixture.service.createUpgradeDraft(fixture.session, id);
        fixture.service.saveDraft(
            fixture.session,
            id,
            modifiedYaml(key)
        );
        Flow version2 = fixture.service.publish(
            fixture.session,
            id
        );
        Flow version1History = flow(
            fixture,
            id,
            1L,
            FlowStatus.CLOSED
        ).orElseThrow();

        // PASS-S2-02
        assertEquals(2L, version2.reversion());
        assertEquals("已修改 Flow", version2.description());
        assertAutomaticTasks(version2, "start", "finish");
        assertEquals("初始 Flow", version1History.description());
        assertAutomaticTasks(version1History, "start");

        fixture.service.createUpgradeDraft(fixture.session, id);
        Flow closed = fixture.service.close(
            fixture.session,
            id
        );

        // PASS-S2-03
        assertEquals(FlowStatus.CLOSED, closed.status());
        assertEquals(2L, closed.reversion());
        assertEquals("已修改 Flow", closed.description());
        assertAutomaticTasks(closed, "start", "finish");
        assertTrue(flow(
            fixture,
            id,
            null,
            FlowStatus.DRAFT
        ).isEmpty());
        assertEquals(
            closed,
            flow(
            fixture,
            id,
            2L,
            FlowStatus.CLOSED
        ).orElseThrow()
        );
    }

    @Test
    void s3RejectsInvalidDefinitionsAndLifecycleOperationsAtomically() {
        Fixture fixture = fixture();
        String emptyId = fixture.service.saveDraft(
            fixture.session,
            """
            key: uc01-s3-empty
            tasks: []
            """
        ).id();
        Flow emptyBefore = flow(
            fixture,
            emptyId,
            null,
            FlowStatus.DRAFT
        ).orElseThrow();
        // PASS-S3-01
        assertThrows(
            WorkflowException.class,
            () -> fixture.service.publish(fixture.session, emptyId)
        );
        assertEquals(
            emptyBefore,
            flow(fixture, emptyId, null, FlowStatus.DRAFT).orElseThrow()
        );

        String duplicateId = fixture.service.saveDraft(
            fixture.session,
            """
            key: uc01-s3-duplicate
            tasks:
              - key: repeated
                type: AUTO
              - key: repeated
                type: AUTO
            """
        ).id();
        Flow duplicateBefore = flow(
            fixture,
            duplicateId,
            null,
            FlowStatus.DRAFT
        ).orElseThrow();
        assertThrows(
            WorkflowException.class,
            () -> fixture.service.publish(fixture.session, duplicateId)
        );
        assertEquals(
            duplicateBefore,
            flow(fixture, duplicateId, null, FlowStatus.DRAFT).orElseThrow()
        );

        String deployedId = fixture.service.saveDraft(
            fixture.session,
            initialYaml("uc01-s3-deployed")
        ).id();
        Flow deployed = fixture.service.publish(
            fixture.session,
            deployedId
        );
        assertThrows(
            WorkflowException.class,
            () -> fixture.service.publish(fixture.session, deployedId)
        );
        assertEquals(
            deployed,
            flow(
                fixture,
                deployedId,
                1L,
                FlowStatus.DEPLOYED
            ).orElseThrow()
        );

        Flow upgradeDraft =
            fixture.service.createUpgradeDraft(
                fixture.session,
                deployedId
            );
        assertThrows(
            WorkflowException.class,
            () -> fixture.service.createUpgradeDraft(
                fixture.session,
                deployedId
            )
        );
        assertEquals(
            upgradeDraft,
            flow(
                fixture,
                deployedId,
                null,
                FlowStatus.DRAFT
            ).orElseThrow()
        );

        String draftOnlyId = fixture.service.saveDraft(
            fixture.session,
            initialYaml("uc01-s3-draft-only")
        ).id();
        Flow draftOnly = flow(
            fixture,
            draftOnlyId,
            null,
            FlowStatus.DRAFT
        ).orElseThrow();
        assertThrows(
            WorkflowException.class,
            () -> fixture.service.close(fixture.session, draftOnlyId)
        );
        // PASS-S3-02
        assertEquals(
            draftOnly,
            flow(
                fixture,
                draftOnlyId,
                null,
                FlowStatus.DRAFT
            ).orElseThrow()
        );
    }

    @Test
    void s4IsolatesFlowReadsAndWritesByTenant() {
        Fixture fixture = fixture();
        Session<User> otherCompany = fixture.sessionFor("company-2");
        Flow first = fixture.service.saveDraft(
            fixture.session,
            yaml("uc01-s4-shared", "第一家公司 Flow", "first")
        );
        Flow second = fixture.service.saveDraft(
            otherCompany,
            yaml("uc01-s4-shared", "第二家公司 Flow", "second")
        );

        // PASS-S4-01
        assertTrue(fixture.service.flow(
            fixture.session,
            second.id(),
            null,
            FlowStatus.DRAFT
        ).isEmpty());
        // PASS-S4-02
        assertThrows(
            WorkflowException.class,
            () -> fixture.service.saveDraft(
                fixture.session,
                second.id(),
                yaml("uc01-s4-shared", "越权修改", "changed")
            )
        );
        assertEquals(
            "第一家公司 Flow",
            fixture.service.flow(
                fixture.session,
                first.id(),
                null,
                FlowStatus.DRAFT
            ).orElseThrow().description()
        );
        assertEquals(
            "第二家公司 Flow",
            fixture.service.flow(
                otherCompany,
                second.id(),
                null,
                FlowStatus.DRAFT
            ).orElseThrow().description()
        );
    }

    @Test
    void s5RequiresExactVersionAndStatusReferences() {
        Fixture fixture = fixture();
        String id = fixture.service.saveDraft(
            fixture.session,
            initialYaml("uc01-s5-flow")
        ).id();
        fixture.service.publish(fixture.session, id);
        fixture.service.createUpgradeDraft(fixture.session, id);
        fixture.service.saveDraft(
            fixture.session,
            id,
            modifiedYaml("uc01-s5-flow")
        );
        fixture.service.publish(fixture.session, id);
        fixture.service.createUpgradeDraft(fixture.session, id);

        // PASS-S5-01
        assertTrue(flow(
            fixture,
            id,
            3L,
            FlowStatus.DEPLOYED
        ).isEmpty());
        // PASS-S5-02
        assertTrue(flow(
            fixture,
            id,
            1L,
            FlowStatus.DEPLOYED
        ).isEmpty());
        assertTrue(flow(
            fixture,
            id,
            2L,
            FlowStatus.CLOSED
        ).isEmpty());
        assertThrows(
            IllegalArgumentException.class,
            () -> flow(fixture, id, 1L, FlowStatus.DRAFT)
        );
    }

    @Test
    void s6KeepsTaskIdentityStableAcrossVersions() {
        Fixture fixture = fixture();
        String id = fixture.service.saveDraft(
            fixture.session,
            initialYaml("uc01-s6-flow")
        ).id();
        Flow version1 = fixture.service.publish(
            fixture.session,
            id
        );
        String startId = version1.tasks().getFirst().id();
        fixture.service.createUpgradeDraft(fixture.session, id);
        fixture.service.saveDraft(
            fixture.session,
            id,
            modifiedYaml("uc01-s6-flow")
        );
        Flow version2 = fixture.service.publish(
            fixture.session,
            id
        );
        Flow history = flow(
            fixture,
            id,
            1L,
            FlowStatus.CLOSED
        ).orElseThrow();

        // PASS-S6-01
        assertEquals(startId, version2.tasks().getFirst().id());
        // PASS-S6-02
        assertNotEquals(
            startId,
            version2.tasks().get(1).id()
        );
        assertAutomaticTasks(history, "start");
    }

    @Test
    void s7RejectsAStaleDraftSaveWithoutOverwritingCommittedContent() {
        Fixture fixture = fixture();
        Flow baseline = fixture.service.saveDraft(
            fixture.session,
            initialYaml("uc01-s7-flow")
        );
        long sharedRevision = baseline.revision();

        Flow committed = fixture.service.saveDraft(
            fixture.session,
            baseline.id(),
            sharedRevision,
            yaml("uc01-s7-flow", "第一个调用方", "start", "first")
        );

        // PASS-S7-01
        WorkflowException conflict = assertThrows(
            WorkflowException.class,
            () -> fixture.service.saveDraft(
                fixture.session,
                baseline.id(),
                sharedRevision,
                yaml(
                    "uc01-s7-flow",
                    "陈旧的第二个调用方",
                    "start",
                    "second"
                )
            )
        );
        assertTrue(conflict.getMessage().contains("revision conflict"));

        Flow reloaded = flow(
            fixture,
            baseline.id(),
            null,
            FlowStatus.DRAFT
        ).orElseThrow();
        // PASS-S7-02
        assertEquals(committed.revision(), reloaded.revision());
        assertEquals("第一个调用方", reloaded.description());
        assertAutomaticTasks(reloaded, "start", "first");
    }

    private Fixture fixture() {
        WorkflowUcFixture workflow = WorkflowUcFixture.open();
        fixtures.add(workflow);
        return new Fixture(workflow);
    }

    private static Optional<Flow> flow(
        Fixture fixture,
        String id,
        Long version,
        FlowStatus status
    ) {
        return fixture.service.flow(
            fixture.session,
            id,
            version,
            status
        );
    }

    private static String initialYaml(String key) {
        return yaml(key, "初始 Flow", "start");
    }

    private static String modifiedYaml(String key) {
        return yaml(key, "已修改 Flow", "start", "finish");
    }

    private static String yaml(
        String key,
        String description,
        String... taskKeys
    ) {
        String tasks = List.of(taskKeys).stream()
            .map(taskKey -> """
                  - key: %s
                    type: AUTO
                """.formatted(taskKey))
            .reduce("", String::concat);
        return """
            key: %s
            description: %s
            tasks:
            %s
            """.formatted(key, description, tasks.indent(2));
    }

    private static void assertAutomaticTasks(
        Flow flow,
        String... expected
    ) {
        assertEquals(
            List.of(expected),
            flow.tasks().stream().map(Task::key).toList()
        );
        for (Task task : flow.tasks()) {
            assertEquals("AUTO", task.type());
            assertEquals(List.of(), task.inputs());
            assertEquals(List.of(), task.outputs());
            assertEquals("DIRECT", task.route());
            assertEquals(Map.of(), task.properties());
            assertEquals(List.of(), task.tasks());
        }
    }

    private static final class Fixture {

        private final WorkflowUcFixture workflow;
        private final FlowService service;
        private final Session<User> session;

        private Fixture(WorkflowUcFixture workflow) {
            this.workflow = workflow;
            this.service = workflow.flowService();
            this.session = workflow.session();
        }

        private Session<User> sessionFor(String tenantLabel) {
            return workflow.sessionFor(tenantLabel);
        }

        private FlowService service() {
            return service;
        }

        private Session<User> session() {
            return session;
        }
    }

}
