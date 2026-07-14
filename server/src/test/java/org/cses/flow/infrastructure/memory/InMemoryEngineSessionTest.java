package org.cses.flow.infrastructure.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.cses.flow.definition.model.Flow;
import org.cses.flow.definition.model.Node;
import org.cses.flow.definition.model.NodeType;
import org.cses.flow.runtime.model.Process;
import org.cses.flow.runtime.query.RuntimeQuery;
import org.cses.flow.runtime.session.EngineTransaction;
import org.cses.flow.runtime.session.RuntimeSession;
import org.junit.jupiter.api.Test;

final class InMemoryEngineSessionTest {

    @Test
    void publishesWritesOnlyWhenTheSharedTransactionCommits() {
        InMemoryFlowRepository flows = new InMemoryFlowRepository();
        InMemoryRuntimeState state = new InMemoryRuntimeState();
        InMemoryEngineSessionFactory sessions = new InMemoryEngineSessionFactory(flows, state);
        RuntimeQuery query = new InMemoryRuntimeQuery(state);
        Flow flow = deployedFlow();
        flows.save(flow);

        EngineTransaction rolledBack = sessions.openTransaction();
        RuntimeSession rolledBackRuntime = sessions.openRuntimeSession(rolledBack);
        rolledBackRuntime.insert(new Process("process-rollback", flow, Map.of()));
        rolledBack.rollback();
        rolledBack.close();

        assertTrue(query.findProcessById("process-rollback").isEmpty());

        EngineTransaction committed = sessions.openTransaction();
        RuntimeSession committedRuntime = sessions.openRuntimeSession(committed);
        committedRuntime.insert(new Process("process-commit", flow, Map.of()));
        committed.commit();
        committed.close();

        assertEquals("process-commit", query.findProcessById("process-commit").orElseThrow().id());
        assertEquals(1, state.committedTransactionCount());
    }

    private Flow deployedFlow() {
        Flow flow = Flow.draft(
                "flow-1",
                "flow-key",
                "flow-name",
                List.of(new Node("start", "start", NodeType.START, Map.of())),
                List.of());
        flow.markDeployed(1);
        return flow;
    }
}
