package org.cses.flow.core.services.triggers;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.cses.flow.core.domains.flows.FlowId;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.triggers.Trigger;
import org.cses.flow.core.repositories.flows.FlowRepository;
import org.cses.flow.core.utils.SessionUtil;
import org.cses.flow.core.utils.RequiredUtil;
import org.cses.flow.extensions.triggers.WebhookTrigger;
import org.cses.flow.infrastructure.jooq.FlowJooqCondition;
import org.cses.flow.infrastructure.repositories.triggers.entries.TriggerRunEntry;
import org.cses.flow.scheduler.TriggerRuntime;
import org.cses.flow.scheduler.events.SchedulerEvent;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.paas.common.util.StringUtil;
import org.paas.session.Session;
import org.paas.session.SessionUser;
import org.x9.jooq.JOOQ;
import java.util.Map;
import static org.flow.gen.flow.Tables.TRIGGER_RUNS;

/** 以可信会话接受 Webhook 请求并持久化启动事实。 */
@Singleton
@Requires(condition = FlowJooqCondition.class)
public class WebhookTriggerService {
    private DSLContext dsl;
    private FlowRepository flows;
    private TriggerRuntime runtime;
    /**
     * @param jooq Flow 数据源
     * @param flows Flow 定义仓储
     * @param runtime 通用命中持久化入口
     */
    public WebhookTriggerService(@Named("flow") JOOQ jooq, FlowRepository flows, TriggerRuntime runtime) {
        this.dsl = jooq.createDSLContext(); this.flows = flows; this.runtime = runtime;
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
            context.select(DSL.field("pg_advisory_xact_lock(hashtextextended({0}, 0))", Object.class,
                DSL.val(company + ":" + flowKey + ":" + triggerKey + ":" + source))).fetch();
            var previous = context.selectFrom(TRIGGER_RUNS).where(TRIGGER_RUNS.COMPANY_ID.eq(company))
                .and(TRIGGER_RUNS.FLOW_KEY.eq(flowKey)).and(TRIGGER_RUNS.TRIGGER_KEY.eq(triggerKey))
                .and(TRIGGER_RUNS.SOURCE_KEY.eq(source)).fetchOneInto(TriggerRunEntry.class);
            if (previous != null) {
                var originalFlow = flows.findByFlowId(context, FlowId.from(company, flowKey, previous.flowVersion))
                    .orElseThrow(() -> new IllegalArgumentException("Original Flow is unavailable"));
                var originalTrigger = originalFlow.triggers().stream().filter(value -> value.key().equals(triggerKey))
                    .findFirst().orElseThrow();
                if (!previous.to().inputs().equals(bind(originalFlow, originalTrigger, supplied)))
                    throw new IllegalArgumentException("Idempotency-Key already used with different inputs");
                return previous.executionId;
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
