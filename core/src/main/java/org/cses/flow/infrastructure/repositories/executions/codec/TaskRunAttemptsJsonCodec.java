package org.cses.flow.infrastructure.repositories.executions.codec;

import org.cses.flow.core.domains.executions.TaskRunAttempt;
import org.cses.flow.core.domains.flows.State;
import org.paas.json.JsonObject;
import org.paas.json.JsonObjects;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;

/** 按调用顺序保存完整尝试事实。 */
public class TaskRunAttemptsJsonCodec {
    /** 禁止实例化。 */
    private TaskRunAttemptsJsonCodec() {}

    /**
     * 编码尝试列表，不修改源记录。
     * @param attempts 非空引用的有序历史
     * @return 新建 JSON 数组
     */
    public static JsonObjects encode(List<TaskRunAttempt> attempts) {
        JsonObjects values = JsonObjects.Create();
        for (var attempt : attempts) values.add(JsonObject.Create()
            .put("number", attempt.number()).put("startedAt", attempt.startedAt())
            .put("completedAt", attempt.completedAt()).put("state", attempt.state().name())
            .put("error", attempt.error()));
        return values;
    }

    /**
     * 解码当前结构，非法持久化事实直接拒绝。
     * @param values 非 null 的数组
     * @return 独立不可变历史
     * @throws IllegalArgumentException 字段缺失或事实不合法时抛出
     */
    public static List<TaskRunAttempt> decode(JsonObjects values) {
        Objects.requireNonNull(values, "Attempt history");
        List<TaskRunAttempt> attempts = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            JsonObject item = values.getObject(index);
            if (!item.has("number") || !item.has("startedAt") || !item.has("state")) throw new IllegalArgumentException("Incomplete attempt");
            attempts.add(TaskRunAttempt.rehydrate(item.getInt("number"), item.getLong("startedAt"),
                item.asMap().get("completedAt") == null ? null : item.getLong("completedAt"),
                State.Type.valueOf(item.getString("state")), (String) item.asMap().get("error")));
        }
        return List.copyOf(attempts);
    }
}
