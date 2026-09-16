package org.cses.flow.infrastructure.repositories.flows.codec;

import org.cses.flow.core.domains.triggers.Trigger;
import org.cses.flow.core.serializers.JacksonMapper;
import org.paas.json.JsonObjects;
import java.util.List;

/** 复用插件绑定恢复触发器定义快照。 */
public class TriggerJsonCodec {
    /** 禁止实例化静态转换器。 */
    private TriggerJsonCodec() {}
    /**
     * 编码触发器配置。
     * @param triggers 非空定义集合
     * @return 包含原技术身份的 PAAS JSON 数组
     */
    public static JsonObjects encode(List<Trigger> triggers) {
        return JsonObjects.FromList(triggers.stream()
            .map(JacksonMapper::toPersistenceMap).toList());
    }
    /**
     * 恢复定义，不生成新身份。
     * @param value 非空的 PAAS JSON 数组
     * @return 只读触发器列表
     */
    public static List<Trigger> decode(JsonObjects value) {
        if (value == null) throw new IllegalArgumentException("Persisted Flow.triggers is null");
        return value.asObjects(java.util.Map.class).stream()
            .map(map -> JacksonMapper.convertPersistenceValue(map, Trigger.class)).toList();
    }
}
