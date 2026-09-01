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
        RUNNING,
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
            || current == Type.SKIPPED
            || current == Type.WARNING
            || current == Type.FAILED
            || current == Type.KILLED;
    }

    public State withState(Type target) {
        Objects.requireNonNull(target, "Target state type");
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
        return copied;
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
