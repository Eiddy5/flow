package org.cses.flow.core.domains.flows;

import org.cses.flow.core.utils.TimeUtil;

import org.cses.flow.core.exceptions.WorkflowException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable workflow runtime state and its ordered transition history.
 */
public class State {

    public enum Type {
        CREATED,
        SUBMITTED,
        RUNNING,
        RETRY_WAIT,
        PAUSED,
        RESTARTED,
        SUCCESS,
        SKIPPED,
        WARNING,
        FAILED,
        KILLING,
        KILLED
    }

    private Type current;
    private List<History> history;

    private State(Type current, List<History> history) {
        this.current = Objects.requireNonNull(
            current,
            "Current state type"
        );
        this.history = validateAndCopyHistory(current, history);
    }

    public static State created() {
        History created = History.now(Type.CREATED);
        return new State(Type.CREATED, List.of(created));
    }

    /**
     * 根据持久化的当前类型和完整历史重建 State，校验首尾及相邻迁移，不追加新记录。
     *
     * @param current 当前类型，不能为 {@code null}，必须与历史末项一致
     * @param history 待复制的非空历史，列表及元素不能为 {@code null}；不修改输入
     * @return 保存精确轨迹的不可变状态，不共享可变历史列表
     * @throws NullPointerException 当当前类型、历史列表或历史元素为 {@code null} 时抛出
     * @throws IllegalArgumentException 当历史首尾或相邻迁移非法时抛出，应核对持久化数据
     */
    public static State rehydrate(
        Type current,
        List<History> history
    ) {
        return new State(current, history);
    }

    public Type current() {
        return current;
    }

    public List<History> history() {
        return history;
    }

    public boolean is(Type expected) {
        return current == Objects.requireNonNull(
            expected,
            "Expected state type"
        );
    }

    /**
     * 判断是否仍处于创建、提交、运行、重试、暂停或受控关闭阶段，不修改状态。
     * @return 尚未收敛到终态时为 true
     */
    public boolean isActive() {
        return current == Type.RETRY_WAIT
            || current == Type.SUBMITTED
            || current == Type.CREATED
            || current == Type.RUNNING
            || current == Type.RESTARTED
            || current == Type.KILLING;
    }

    public boolean isPaused() {
        return current == Type.PAUSED;
    }

    public boolean isTerminal() {
        return current == Type.SUCCESS
            || current == Type.SKIPPED
            || current == Type.WARNING
            || current == Type.FAILED
            || current == Type.KILLED;
    }

    /**
     * 校验目标类型并追加当前时间的状态历史，返回独立的新 State；原对象保持不变。
     *
     * @param target 待迁移的状态类型，不能为 {@code null} 或与当前状态相同
     * @return 包含原历史及一条新记录的不可变状态，不共享可变历史列表
     * @throws NullPointerException 当目标为 {@code null} 时抛出
     * @throws WorkflowException 当迁移不属于通用合法超集时抛出，调用方应核对领域动作
     */
    public State withState(Type target) {
        Objects.requireNonNull(target, "Target state type");
        if (!isAllowedTransition(current, target)) {
            throw new WorkflowException(
                "Invalid state transition: " + current + " -> " + target
            );
        }
        List<History> changed = new ArrayList<>(history);
        changed.add(History.now(target));
        return new State(target, changed);
    }

    public State running() {
        return withState(Type.RUNNING);
    }

    public State paused() {
        return withState(Type.PAUSED);
    }

    public State restarted() {
        return withState(Type.RESTARTED);
    }

    public State success() {
        return withState(Type.SUCCESS);
    }

    public State skipped() {
        return withState(Type.SKIPPED);
    }

    public State warning() {
        return withState(Type.WARNING);
    }

    public State failed() {
        return withState(Type.FAILED);
    }

    public State killing() {
        return withState(Type.KILLING);
    }

    public State killed() {
        return withState(Type.KILLED);
    }

    /**
     * 复制并检查持久化历史的首尾及相邻迁移；列表顺序决定轨迹，不要求时间递增。
     *
     * @param current 期望的当前状态，不能为 {@code null}，必须等于最后一条历史状态
     * @param source 待读取的非空历史列表，列表和元素不能为 {@code null}；不会修改输入
     * @return 包含原记录的不可变列表，记录本身不可变
     * @throws NullPointerException 当列表或其中元素为 {@code null} 时抛出
     * @throws IllegalArgumentException 当首尾或相邻迁移不合法时抛出，调用方应检查持久化数据
     */
    private static List<History> validateAndCopyHistory(
        Type current,
        List<History> source
    ) {
        Objects.requireNonNull(source, "State history");
        List<History> copied = List.copyOf(source);
        if (copied.isEmpty()) {
            throw new IllegalArgumentException(
                "State history must not be empty"
            );
        }
        if (copied.getFirst().state() != Type.CREATED) {
            throw new IllegalArgumentException(
                "State history must start with CREATED"
            );
        }
        if (copied.getLast().state() != current) {
            throw new IllegalArgumentException(
                "Current state must match the last history entry"
            );
        }
        for (int index = 1; index < copied.size(); index++) {
            Type previous = copied.get(index - 1).state();
            Type next = copied.get(index).state();
            if (!isAllowedTransition(previous, next)) {
                throw new IllegalArgumentException(
                    "Invalid state history transition: " + previous + " -> " + next
                );
            }
        }
        return copied;
    }

    /**
     * 判断两个状态是否属于 Execution 与 TaskRun 路线的合法超集，包括受控祖先重开；
     * 不修改状态，各状态拥有者仍需执行自己的更窄校验。
     *
     * @param source 当前状态，不能为 {@code null}
     * @param target 目标状态，不能为 {@code null}
     * @return 至少一个运行对象支持该迁移时返回 {@code true}，其余返回 {@code false}
     */
    private static boolean isAllowedTransition(Type source, Type target) {
        return switch (source) {
            case CREATED -> target == Type.SUBMITTED || target == Type.RUNNING
                || target == Type.FAILED
                || target == Type.KILLING
                || target == Type.KILLED;
            case SUBMITTED -> target == Type.RUNNING || target == Type.FAILED || target == Type.KILLING || target == Type.KILLED;
            case RUNNING -> target == Type.RETRY_WAIT
                || target == Type.PAUSED
                || target == Type.SUCCESS
                || target == Type.SKIPPED
                || target == Type.WARNING
                || target == Type.FAILED
                || target == Type.KILLING
                || target == Type.KILLED;
            case RETRY_WAIT -> target == Type.CREATED
                || target == Type.FAILED
                || target == Type.KILLED;
            case PAUSED -> target == Type.RUNNING
                || target == Type.RESTARTED
                || target == Type.KILLING
                || target == Type.KILLED;
            case RESTARTED -> target == Type.RUNNING || target == Type.KILLING;
            case SUCCESS, WARNING -> target == Type.RUNNING;
            case KILLING -> target == Type.KILLED;
            case SKIPPED, FAILED, KILLED -> false;
        };
    }

    @Override
    public boolean equals(Object value) {
        return this == value
            || value instanceof State other
            && current == other.current
            && history.equals(other.history);
    }

    @Override
    public int hashCode() {
        return Objects.hash(current, history);
    }

    @Override
    public String toString() {
        return current.name();
    }

    public static class History {

        private Type state;
        private long date;

        private History(Type state, long date) {
            this.state = Objects.requireNonNull(
                state,
                "History state type"
            );
            if (date < 0) {
                throw new IllegalArgumentException(
                    "History date must not be negative"
                );
            }
            this.date = date;
        }

        private static History now(Type state) {
            return new History(state, TimeUtil.now());
        }

        /**
         * Rehydrates one recorded transition from trusted persistence data.
         */
        public static History rehydrate(Type state, long date) {
            return new History(state, date);
        }

        public Type state() {
            return state;
        }

        public long date() {
            return date;
        }

        @Override
        public boolean equals(Object value) {
            return this == value
                || value instanceof History other
                && state == other.state
                && date == other.date;
        }

        @Override
        public int hashCode() {
            return Objects.hash(state, date);
        }

        @Override
        public String toString() {
            return state + "@" + date;
        }
    }
}
