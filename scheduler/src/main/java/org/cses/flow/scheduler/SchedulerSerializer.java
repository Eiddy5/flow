package org.cses.flow.scheduler;

import com.github.kagkarlsson.scheduler.serializer.Serializer;
import org.paas.json.JsonObject;
import java.nio.charset.StandardCharsets;

/** 调度任务数据复用 PAAS JSON，避免 Java 对象反序列化。 */
public class SchedulerSerializer implements Serializer {
    /**
     * @param data 可空数据
     * @return UTF-8 JSON 字节 */
    @Override
    public byte[] serialize(Object data) {
        return data == null ? null : JsonObject.ToJsonContent(data).getBytes(StandardCharsets.UTF_8);
    }
    /**
     * @param clazz 已注册的数据类型
     * @param data JSON 字节
     * @return 恢复的调度数据 */
    @Override
    public <T> T deserialize(Class<T> clazz, byte[] data) {
        return data == null ? null : JsonObject.Parse(new String(data, StandardCharsets.UTF_8)).asObject(clazz);
    }
}
