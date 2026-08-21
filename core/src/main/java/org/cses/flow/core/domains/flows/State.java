package org.cses.flow.core.domains.flows;

import org.cses.flow.core.exceptions.WorkflowException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable workflow runtime state and its ordered transition history.
 */
public final class State {

    public enum Type {
        CREATED,

        RUNNING,
        PAUSED,
        RESTARTED,
        SUCCESS,
        WARNING,
        FAILED,
        KILLING,
        KILLED
    }

    private final Type current;
    private final List<History> history;

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
     * Rehydrates an exact state trajectory from a trusted persistence adapter.
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

    public boolean isActive() {
        return current == Type.CREATED
            || current == Type.RUNNING
            || current == Type.RESTARTED
            || current == Type.KILLING;
    }

    public boolean isPaused() {
        return current == Type.PAUSED;
    }

    public boolean isTerminal() {
        return current == Type.SUCCESS
            || current == Type.WARNING
            || current == Type.FAILED
            || current == Type.KILLED;
    }

    public State withState(Type target) {
        Objects.requireNonNull(target, "Target state type");
        if (!isAllowedTransition(current, target)) {
            throw new WorkflowException(
                "State cannot transition from " + current + " to " + target
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
                    "Invalid state history transition from "
                        + previous + " to " + next
                );
            }
        }
        return copied;
    }

    private static boolean isAllowedTransition(Type source, Type target) {
        return switch (source) {
            case CREATED ->
                target == Type.RUNNING
                    || target == Type.KILLING
                    || target == Type.KILLED;
            case RUNNING ->
                target == Type.PAUSED
                    || target == Type.SUCCESS
                    || target == Type.WARNING
                    || target == Type.FAILED
                    || target == Type.KILLING
                    || target == Type.KILLED;
            case PAUSED ->
                target == Type.RUNNING
                    || target == Type.RESTARTED
                    || target == Type.KILLING
                    || target == Type.KILLED;
            case RESTARTED ->
                target == Type.RUNNING || target == Type.KILLING;
            case KILLING -> target == Type.KILLED;
            case SUCCESS, WARNING, FAILED, KILLED -> false;
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

    public static final class History {

        private final Type state;
        private final long date;

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
            return new History(state, System.currentTimeMillis());
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
