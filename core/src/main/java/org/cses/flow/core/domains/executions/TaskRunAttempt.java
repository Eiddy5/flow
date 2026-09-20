package org.cses.flow.core.domains.executions;

import org.cses.flow.core.domains.flows.State;

/** 同一 TaskRun 的一次调用事实，创建后不可变。 */
public class TaskRunAttempt {
    private int number;
    private long startedAt;
    private Long completedAt;
    private State.Type state;
    private String error;

    /**
     * 校验一次调用事实。
     * @param number 从 1 开始的编号
     * @param startedAt 非负开始时间，毫秒
     * @param completedAt 结束毫秒时间，运行时为 null
     * @param state RUNNING 或 Worker 终态
     * @param error 仅 FAILED 时必填
     * @throws IllegalArgumentException 状态或时间不匹配时抛出
     */
    private TaskRunAttempt(int number, long startedAt, Long completedAt, State.Type state, String error) {
        if (number < 1 || startedAt < 0 || completedAt != null && completedAt < startedAt
            || state == null || !(state == State.Type.RUNNING || state == State.Type.SUCCESS
            || state == State.Type.WARNING || state == State.Type.FAILED || state == State.Type.KILLED)
            || (state == State.Type.RUNNING) != (completedAt == null)
            || (state == State.Type.FAILED ? error == null || error.isBlank() : error != null)) {
            throw new IllegalArgumentException("Invalid TaskRun attempt");
        }
        this.number = number;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.state = state;
        this.error = error;
    }

    /**
     * 恢复或记录一次精确调用，不生成身份或时间。
     * @param number 正数编号
     * @param startedAt 非负开始毫秒时间
     * @param completedAt 可空结束毫秒时间
     * @param state 调用状态
     * @param error 仅失败时必填
     * @return 不可变调用事实
     */
    public static TaskRunAttempt rehydrate(int number, long startedAt, Long completedAt, State.Type state, String error) {
        return new TaskRunAttempt(number, startedAt, completedAt, state, error);
    }
    /** @return 从 1 开始的编号 */
    public int number() { return number; }
    /** @return 开始毫秒时间 */
    public long startedAt() { return startedAt; }
    /** @return 结束毫秒时间，未结束为 null */
    public Long completedAt() { return completedAt; }
    /** @return 本次调用状态 */
    public State.Type state() { return state; }
    /** @return 失败原因，非失败为 null */
    public String error() { return error; }
}
