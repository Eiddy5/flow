package org.cses.flow.controller.flow;

import com.fasterxml.jackson.annotation.JsonTypeName;
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
    /** 验证 HTTP 视图保留业务类型和自有配置，同时保留内置输入、输出协议。 */
    @Test
    void preservesBusinessConfigurationAndBuiltInDataContracts() {
        JsonFactory.instance = JsonMapper.createDefault();
        Input<?> business = new BusinessInput();
        Input<?> integer = IntegerInput.builder().key("count").min(1).max(3).build();
        ActorRef actor = ActorRef.create("tester", "Tester");
        Flow flow = Flow.rehydrate("flow-view", "company", "view", true, 1L,
            null, Map.of(), List.of(business, integer), List.of(Output.create("result", DataType.STRING)),
            List.of(), RecordState.Open, actor, actor, null, 1L, 1L, null, "key: view");
        FlowModels.FlowView view = FlowModels.FlowView.from(flow);
        assertEquals("BUSINESS_VIEW", view.getInputs().getFirst().getType());
        assertEquals(Map.of("prefix", "EMP"), view.getInputs().getFirst().getConstraints());
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

    /** 只用于 HTTP 投影的业务字段样本；类型发现与业务行为由 Core 的外部插件测试覆盖。 */
    @JsonTypeName("BUSINESS_VIEW")
    public static class BusinessInput extends Input<String> {
        private String prefix;

        /** 创建完整定义并由 Input 自己完成校验。 */
        private BusinessInput() {
            super("employee", null, false, null);
            prefix = "EMP";
            validateDefinition();
        }

        /** @return 供 HTTP 保留的业务配置 */
        public String getPrefix() {
            return prefix;
        }

        /** @return 业务值使用 STRING */
        @Override
        public DataType getValueType() {
            return DataType.STRING;
        }

        /**
         * 接受字符串传输值。
         * @param value 非空原始值，只读
         * @return 原字符串
         * @throws IllegalArgumentException 当类型不符时抛出
         */
        @Override
        protected String convert(Object value) {
            return (String) DataType.STRING.normalize(value);
        }
    }
}
