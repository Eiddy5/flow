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
        WAITING,
        COMPLETED,
        TERMINATED
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
        return current == Type.CREATED || current == Type.RUNNING;
    }

    public boolean isWaiting() {
        return current == Type.WAITING;
    }

    public boolean isTerminal() {
        return current == Type.COMPLETED || current == Type.TERMINATED;
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

    public State waiting() {
        return withState(Type.WAITING);
    }

    public State complete() {
        return withState(Type.COMPLETED);
    }

    public State fail() {
        return withState(Type.TERMINATED);
    }

    public State terminate() {
        return withState(Type.TERMINATED);
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
                target == Type.RUNNING || target == Type.TERMINATED;
            case RUNNING ->
                target == Type.WAITING
                    || target == Type.COMPLETED
                    || target == Type.TERMINATED;
            case WAITING ->
                target == Type.RUNNING
                    || target == Type.COMPLETED
                    || target == Type.TERMINATED;
            case COMPLETED, TERMINATED -> false;
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
