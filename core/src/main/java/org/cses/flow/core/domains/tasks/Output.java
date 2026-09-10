package org.cses.flow.core.domains.tasks;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.cses.flow.core.domains.flows.State;

import java.util.Optional;

public interface Output {
    /**
     * 声明任务完成状态，未指定时由执行边界采用 SUCCESS。
     * @return 非 null 的状态容器；仅允许 SUCCESS、WARNING、FAILED 或 KILLED
     */
    @JsonIgnore
    default Optional<State.Type> state() {
        return Optional.empty();
    }

    /**
     * 提供失败原因，不作为业务输出字段发布。
     * @return FAILED 时须为非空白原因；其他状态返回空容器
     */
    @JsonIgnore
    default Optional<String> error() {
        return Optional.empty();
    }
}
