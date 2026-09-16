package org.cses.flow.infrastructure.repositories.triggers.codec;

import org.cses.flow.scheduler.events.SchedulerEvent;
import org.jooq.JSONB;
import org.paas.json.JsonObject;
import java.util.Map;

/** 事件及其输入快照的 PAAS JSON 编码边界。 */
public class SchedulerEventJsonCodec {
    /** 禁止实例化静态转换器。 */
    private SchedulerEventJsonCodec() {}

    /**
     * 编码完整事件协议。
     * @param event 非空已校验事件
     * @return 独立的 JSONB 快照
     */
    public static JSONB encode(SchedulerEvent event) {
        return JSONB.valueOf(JsonObject.ToJsonContent(event));
    }

    /**
     * 解码完整事件协议并执行事件构造校验。
     * @param value 非空 JSONB
     * @return 恢复原身份的事件
     * @throws IllegalArgumentException 事件字段非法时抛出
     */
    public static SchedulerEvent decode(JSONB value) {
        return JsonObject.Parse(value.data()).asObject(SchedulerEvent.class);
    }

    /**
     * 编码命中时绑定后的输入。
     * @param inputs 非空输入映射
     * @return 独立的 JSONB 快照
     */
    public static JSONB encodeInputs(Map<String, Object> inputs) {
        return JSONB.valueOf(JsonObject.FromMap(inputs).toJson());
    }

    /**
     * 解码命中记录中的输入。
     * @param value 非空 JSONB 输入快照
     * @return 独立的输入映射
     */
    public static Map<String, Object> decodeInputs(JSONB value) {
        return JsonObject.Parse(value.data()).asMap();
    }
}
