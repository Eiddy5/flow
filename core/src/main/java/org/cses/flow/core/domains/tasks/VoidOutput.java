package org.cses.flow.core.domains.tasks;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.cses.flow.core.domains.flows.State;

import java.util.Optional;

/** 没有业务字段的任务结果，允许携带失败原因。 */
public class VoidOutput implements Output {
    @JsonIgnore
    private String failure;

    /**
     * 构造没有业务字段的结果。
     * @param failure 非空白失败原因；null 表示成功
     */
    private VoidOutput(String failure) {
        this.failure = failure;
    }

    /**
     * 创建空成功结果。
     * @return 新建的无业务字段结果
     */
    public static VoidOutput from() {
        return new VoidOutput(null);
    }

    /**
     * 创建空业务输出的失败结果。
     * @param error 非 null、非空白的失败原因
     * @return 新建的失败结果
     * @throws IllegalArgumentException 原因为 null 或空白时抛出
     */
    public static VoidOutput failed(String error) {
        if (error == null || error.isBlank()) {
            throw new IllegalArgumentException("Failed Output must carry an error");
        }
        return new VoidOutput(error);
    }

    /** @return 有失败原因时为 FAILED，否则由执行边界采用默认成功状态 */
    @Override
    public Optional<State.Type> state() {
        return failure == null ? Optional.empty() : Optional.of(State.Type.FAILED);
    }

    /** @return 已保存的失败原因；成功时为空 */
    @Override
    public Optional<String> error() {
        return Optional.ofNullable(failure);
    }
}
