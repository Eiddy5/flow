package org.cses.flow.extensions.triggers;

import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.triggers.AbstractTrigger;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.plugins.annotations.Example;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.utils.RequiredUtil;
import lombok.Builder;
import java.util.List;

/** 单个同租户上游流程到达指定终态后的触发定义。 */
@Plugin(title = "流程执行触发", description = "上游流程达到指定终态后启动新实例，默认监听成功",
    examples = @Example(title = "上游成功后启动后续流程", full = true, code = """
        key: after-upstream
        triggers:
          - key: upstream-success
            type: org.cses.flow.extensions.triggers.FlowTrigger
            flowKey: upstream
            states: [SUCCESS]
        tasks:
          - key: hello
            type: org.cses.flow.extensions.log.Log
            message: "上游流程已成功"
        """))
@SuperBuilder
@NoArgsConstructor
public class FlowTrigger extends AbstractTrigger {
    @jakarta.validation.constraints.NotBlank
    String flowKey;
    @Builder.Default
    List<State.Type> states = List.of(State.Type.SUCCESS);
    /** @return 监听的同租户上游 Flow key */
    public String flowKey() { return flowKey; }
    /** @return 只读终态集合 */
    public List<State.Type> states() { return List.copyOf(states); }
    /** 校验上游名称和非空终态集合，只支持 SUCCESS、FAILED、KILLED。 */
    @Override
    public void validate() {
        super.validate();
        RequiredUtil.required(flowKey, "Upstream flowKey");
        if (states == null || states.isEmpty() || states.stream().anyMatch(state ->
            state != State.Type.SUCCESS && state != State.Type.FAILED && state != State.Type.KILLED))
            throw new IllegalArgumentException("FlowTrigger states must be terminal");
    }
}
