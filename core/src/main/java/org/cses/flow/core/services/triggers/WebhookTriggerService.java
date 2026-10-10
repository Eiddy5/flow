package org.cses.flow.core.services.triggers;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.flows.FlowId;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.triggers.Trigger;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.repositories.triggers.TriggerRepository;
import org.cses.flow.core.utils.SessionUtil;
import org.cses.flow.core.utils.RequiredUtil;
import org.cses.flow.extensions.triggers.WebhookTrigger;
import org.cses.flow.infrastructure.jooq.FlowJooqCondition;
import org.cses.flow.infrastructure.jooq.FlowDatabase;
import org.cses.flow.scheduler.TriggerRuntime;
import org.cses.flow.scheduler.events.SchedulerEvent;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.paas.common.util.StringUtil;
import org.paas.session.Session;
import org.paas.session.SessionUser;
import org.x9.jooq.JOOQ;
import java.util.Map;

/** 以可信会话接受 Webhook 请求并持久化启动事实。 */
@Singleton
@Requires(condition = FlowJooqCondition.class)
public class WebhookTriggerService {
    private DSLContext dsl;
    private FlowRepository flows;
    private TriggerRuntime runtime;
    private TriggerRepository triggers;
    /**
     * 装配 Webhook 业务入口，持久化查询与幂等锁由触发仓储完成。
     * @param jooq Flow 数据源
     * @param flows Flow 定义仓储
     * @param runtime 通用命中持久化入口
     * @param triggers 触发命中持久化端口
     */
    public WebhookTriggerService(@Named(FlowDatabase.DATA_SOURCE_NAME) JOOQ jooq,
            FlowRepository flows, TriggerRuntime runtime, TriggerRepository triggers) {
        this.dsl = jooq.createDSLContext();
        this.flows = flows;
        this.runtime = runtime;
        this.triggers = triggers;
    }
    /**
     * 接收当前租户的 Webhook，返回已持久化的 Execution ID；运行由队列异步完成。
     * @param session 已认证且选择公司的会话
     * @param flowKey 目标流程
     * @param triggerKey Webhook 配置名称
     * @param requestId 同一操作者重试保持相同的幂等键，最多 128 字符
     * @param inputs 请求输入，不得覆盖触发器静态输入
     *
     * @return 首次命中的稳定实例 ID
     * @throws IllegalArgumentException 参数错误、输入冲突或触发器不可用时抛出
     */
    public String fire(Session<? extends SessionUser> session, String flowKey, String triggerKey,
            String requestId, Map<String, Object> inputs) {
        String company = SessionUtil.company(session);
        String actor = SessionUtil.user(session).id();
        RequiredUtil.required(requestId, "Idempotency-Key");
        RequiredUtil.required(flowKey, "Flow key"); RequiredUtil.required(triggerKey, "Trigger key");
        if (requestId.length() > 128) throw new IllegalArgumentException("Idempotency-Key too long");
        Map<String, Object> supplied = new java.util.LinkedHashMap<>(
            RequiredUtil.required(inputs, "Webhook inputs"));
        String source = "webhook:" + actor.length() + ":" + actor + ":" + requestId;
        return dsl.transactionResult(config -> {
            var context = DSL.using(config);
            triggers.lockSource(context, company, flowKey, triggerKey, source);
            var previous = triggers.findBySource(context, company, flowKey, triggerKey, source).orElse(null);
            if (previous != null) {
                var originalFlow = flows.findByFlowId(context, FlowId.from(company, flowKey, previous.flowVersion()))
                    .orElseThrow(() -> new IllegalArgumentException("Original Flow is unavailable"));
                var originalTrigger = originalFlow.triggers().stream().filter(value -> value.key().equals(triggerKey))
                    .findFirst().orElseThrow();
                if (!previous.inputs().equals(bind(originalFlow, originalTrigger, supplied)))
                    throw new IllegalArgumentException("Idempotency-Key already used with different inputs");
                return previous.executionId();
            }
            var flow = flows.findLatestByFlowId(context, FlowId.from(company, flowKey))
                .filter(value -> !value.deleted()).orElseThrow(() -> new IllegalArgumentException("Flow is unavailable"));
            var trigger = flow.triggers().stream().filter(value -> value.key().equals(triggerKey) && !value.disabled())
                .filter(WebhookTrigger.class::isInstance).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Webhook trigger is unavailable"));
            return runtime.stage(context, SchedulerEvent.occurrence(company, StringUtil.newId(), flow.key(), flow.version(),
                trigger.key(), null, StringUtil.newId(), actor, bind(flow, trigger, supplied), source)).executionId();
        });
    }

    /**
     * @param flow 固定目标版本
     * @param trigger 该版本中的触发器
     * @param supplied 请求输入
     * @return 校验后的完整输入快照
     */
    private Map<String, Object> bind(Flow flow, Trigger trigger, Map<String, Object> supplied) {
        var merged = new java.util.HashMap<String, Object>(trigger.inputs());
        supplied.forEach((key, value) -> {
            if (merged.containsKey(key)) throw new IllegalArgumentException("Cannot override static trigger input: " + key);
            merged.put(key, value);
        });
        return flow.bindInputs(merged);
    }
}
