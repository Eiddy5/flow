package org.cses.flow.infrastructure.repositories.executions.codec;

import org.cses.flow.core.domains.executions.TaskRunAttempt;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.infrastructure.queues.pulsar.PulsarTestEnvironment;
import org.junit.jupiter.api.Test;
import org.paas.json.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 对应 UC 真实回读反例的最小技术回归。 */
class TaskRunAttemptsJsonCodecTest {
    /** 运行中和成功尝试的可空结束时间、错误字段往返保持 null。 */
    @Test
    void nullableAttemptFieldsRoundTrip() {
        PulsarTestEnvironment.initializeJson();
        var running = TaskRunAttempt.rehydrate(1, 1000, null, State.Type.RUNNING, null);
        var result = TaskRunAttemptsJsonCodec.decode(TaskRunAttemptsJsonCodec.encode(List.of(running))).getFirst();
        assertEquals(1, result.number());
        assertEquals(1000, result.startedAt());
        assertNull(result.completedAt());
        assertNull(result.error());
        assertEquals(State.Type.RUNNING, result.state());
        var success = TaskRunAttempt.rehydrate(2, 2000, 2100L, State.Type.SUCCESS, null);
        result = TaskRunAttemptsJsonCodec.decode(TaskRunAttemptsJsonCodec.encode(List.of(success))).getFirst();
        assertEquals(2100, result.completedAt());
        assertNull(result.error());
        assertEquals(State.Type.SUCCESS, result.state());
    }
    /** 嵌套空对象、空数组与显式 null 经 JSON 文本往返不被全局 NON_EMPTY 裁掉。 */
    @Test
    void variableTreeKeepsEmptyContainersAndNulls() {
        PulsarTestEnvironment.initializeJson();
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("outputs", Map.of());
        nested.put("optional", null);
        Map<String, Object> values = Map.of("inputs", Map.of(), "parents", List.of(), "taskRun", nested);
        JsonObject encoded = TaskRunValuesJsonCodec.encodeVariables(values);
        assertEquals(values, TaskRunValuesJsonCodec.decode(JsonObject.Parse(encoded.toString())));
    }

    /** 普通任务输入输出沿用省略空输出键的持久化协议。 */
    @Test
    void taskValuesKeepExistingEmptyOutputProjection() {
        PulsarTestEnvironment.initializeJson();
        JsonObject encoded = TaskRunValuesJsonCodec.encode(Map.of(
                "outputs", Map.of("prepare-release", Map.of()), "value", "kept"));
        assertEquals(Map.of("value", "kept"),
                TaskRunValuesJsonCodec.decode(JsonObject.Parse(encoded.toString())));
    }

}
