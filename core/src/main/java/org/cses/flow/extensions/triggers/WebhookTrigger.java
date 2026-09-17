package org.cses.flow.extensions.triggers;

import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.triggers.AbstractTrigger;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.plugins.annotations.Example;

/** 由已认证 HTTP 请求提供运行输入的触发定义。 */
@Plugin(title = "Webhook 触发", description = "通过已认证 POST 请求触发，使用 Idempotency-Key 保证重试复用实例",
    examples = @Example(title = "通过 Webhook 启动流程", full = true, code = """
        key: webhook-flow
        triggers:
          - key: incoming
            type: org.cses.flow.extensions.triggers.WebhookTrigger
        tasks:
          - key: hello
            type: org.cses.flow.extensions.log.Log
            message: "Webhook 已受理"
        """))
@SuperBuilder
@NoArgsConstructor
public class WebhookTrigger extends AbstractTrigger {
    /** @return true；运行请求可以补充 Flow 声明的输入 */
    @Override
    public boolean acceptsRuntimeInputs() { return true; }
}
