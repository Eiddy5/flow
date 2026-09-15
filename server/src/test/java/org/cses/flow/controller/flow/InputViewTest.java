package org.cses.flow.controller.flow;

import io.micronaut.json.JsonMapper;
import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.domains.flows.Output;
import org.cses.flow.core.domains.flows.inputs.IntegerInput;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonFactory;
import org.paas.json.JsonObject;
import org.paas.session.RecordState;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class InputViewTest {
    /** 验证 HTTP 视图保留内置输入约束和输出协议。 */
    @Test
    void preservesBuiltInConstraintsAndDataContracts() {
        JsonFactory.instance = JsonMapper.createDefault();
        Input<?> integer = IntegerInput.builder().key("count").min(1).max(3).build();
        ActorRef actor = ActorRef.create("tester", "Tester");
        Flow flow = Flow.rehydrate("flow-view", "company", "view", true, 1L,
            null, Map.of(), List.of(integer), List.of(Output.create("result", DataType.STRING)),
            List.of(), RecordState.Open, actor, actor, null, 1L, 1L, null, "key: view");
        FlowModels.FlowView view = FlowModels.FlowView.from(flow);
        assertEquals(Map.of("min", 1, "max", 3), view.getInputs().getLast().getConstraints());
        assertEquals("INTEGER", view.getInputs().getLast().getType());
        assertEquals("STRING", view.getOutputs().getFirst().getType());
        JsonObject serialized = JsonObject.From(view);
        assertFalse(serialized.toJson().contains("valueType"));
    }

    /** 验证 Pause HTTP 视图将子任务和恢复输入序列化为新字段名。 */
    @Test
    void exposesPauseHooksUnderTheirRenamedJsonProperties() {
        JsonFactory.instance = JsonMapper.createDefault();
        var pause = org.cses.flow.extensions.flow.Pause.builder()
            .id("pause-id").key("wait")
            .onPause(org.cses.flow.extensions.log.Log.builder()
                .id("child-id").key("prepare").build())
            .onResume(List.of(IntegerInput.builder().key("count").build()))
            .build();
        ActorRef actor = ActorRef.create("tester", "Tester");
        Flow flow = Flow.rehydrate("pause-view", "company", "view", true, 1L,
            null, Map.of(), List.of(), List.of(), List.of(pause), RecordState.Open,
            actor, actor, null, 1L, 1L, null, "key: view");
        var view = FlowModels.FlowView.from(flow).getTasks().getFirst();
        assertEquals("prepare", view.getOnPause().getKey());
        assertEquals("count", view.getOnResume().getFirst().getKey());
        Map<String, Object> json = JsonObject.From(view).asMap();
        org.junit.jupiter.api.Assertions.assertTrue(json.containsKey("onPause"));
        org.junit.jupiter.api.Assertions.assertTrue(json.containsKey("onResume"));
        assertFalse(json.containsKey("pause"));
        assertFalse(json.containsKey("resume"));
    }

}
