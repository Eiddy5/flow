package org.cses.flow.core.domains.executions;

import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.utils.RequiredUtil;
import org.cses.flow.core.utils.TimeUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Versioned history for one currently iterating runtime fragment.
 *
 * <p>The owner supplies the meaning of the fragment. An Execution uses it for
 * a rewind range, while an iterative orchestration TaskRun uses it for its
 * loop rounds.</p>
 */
public final class Generation {

    private Current current;
    private History history;

    private Generation(Current current, History history) {
        this.current = current;
        this.history = Objects.requireNonNull(history, "Generation history");
        validateVersions();
    }

    public static Generation empty() {
        return new Generation(null, History.empty());
    }

    public static Generation rehydrate(Current current, History history) {
        return new Generation(current, history);
    }

    public Optional<Current> current() {
        return Optional.ofNullable(current);
    }

    public History history() {
        return history;
    }

    public boolean active() {
        return current != null;
    }

    public void start(String reason) {
        start(null, null, reason);
    }

    public void start(
            String sourceTaskRunId,
            String targetTaskRunId,
            String reason
    ) {
        if (current != null) {
            throw new WorkflowException(
                    "Generation already has a current version: "
                            + current.version()
            );
        }
        current = Current.now(
                nextVersion(),
                sourceTaskRunId,
                targetTaskRunId,
                reason
        );
    }

    public void advance(String reason) {
        advance(null, null, reason);
    }

    public void advance(
            String sourceTaskRunId,
            String targetTaskRunId,
            String reason
    ) {
        requireCurrent();
        Current next = Current.now(
                nextVersion(),
                sourceTaskRunId,
                targetTaskRunId,
                reason
        );
        archiveCurrent();
        current = next;
    }

    public void complete() {
        requireCurrent();
        archiveCurrent();
        current = null;
    }

    public Generation copy() {
        return rehydrate(current, History.rehydrate(history.currents()));
    }

    private int nextVersion() {
        if (current != null) {
            return current.version() + 1;
        }
        List<Current> currents = history.currents();
        return currents.isEmpty() ? 1 : currents.getLast().version() + 1;
    }

    private void archiveCurrent() {
        history = history.append(current);
    }

    private void requireCurrent() {
        if (current == null) {
            throw new WorkflowException("Generation has no current version");
        }
    }

    private void validateVersions() {
        int expected = 1;
        for (Current archived : history.currents()) {
            if (archived.version() != expected++) {
                throw new IllegalArgumentException(
                        "Generation versions must be consecutive from 1"
                );
            }
        }
        if (current != null && current.version() != expected) {
            throw new IllegalArgumentException(
                    "Current Generation version must follow its history"
            );
        }
    }

    public static final class Current {

        private final int version;
        private final String sourceTaskRunId;
        private final String targetTaskRunId;
        private final String reason;
        private final long date;

        private Current(
                int version,
                String sourceTaskRunId,
                String targetTaskRunId,
                String reason,
                long date
        ) {
            if (version < 1) {
                throw new IllegalArgumentException(
                        "Generation version must be positive"
                );
            }
            String source = normalizeOptionalText(sourceTaskRunId);
            String target = normalizeOptionalText(targetTaskRunId);
            if ((source == null) != (target == null)) {
                throw new IllegalArgumentException(
                        "Generation source and target must both be present or absent"
                );
            }
            if (date < 0) {
                throw new IllegalArgumentException(
                        "Generation date must not be negative"
                );
            }
            this.version = version;
            this.sourceTaskRunId = source;
            this.targetTaskRunId = target;
            this.reason = requireText(reason, "Generation reason");
            this.date = date;
        }

        private static Current now(
                int version,
                String sourceTaskRunId,
                String targetTaskRunId,
                String reason
        ) {
            return new Current(
                    version,
                    sourceTaskRunId,
                    targetTaskRunId,
                    reason,
                    TimeUtil.now()
            );
        }

        public static Current rehydrate(
                int version,
                String sourceTaskRunId,
                String targetTaskRunId,
                String reason,
                long date
        ) {
            return new Current(
                    version,
                    sourceTaskRunId,
                    targetTaskRunId,
                    reason,
                    date
            );
        }

        public int version() {
            return version;
        }

        public Optional<String> sourceTaskRunId() {
            return Optional.ofNullable(sourceTaskRunId);
        }

        public Optional<String> targetTaskRunId() {
            return Optional.ofNullable(targetTaskRunId);
        }

        public String reason() {
            return reason;
        }

        public long date() {
            return date;
        }

        @Override
        public boolean equals(Object value) {
            return this == value
                    || value instanceof Current other
                    && version == other.version
                    && date == other.date
                    && Objects.equals(sourceTaskRunId, other.sourceTaskRunId)
                    && Objects.equals(targetTaskRunId, other.targetTaskRunId)
                    && reason.equals(other.reason);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    version,
                    sourceTaskRunId,
                    targetTaskRunId,
                    reason,
                    date
            );
        }
    }

    public static final class History {

        private final List<Current> currents;

        private History(List<Current> currents) {
            Objects.requireNonNull(currents, "Generation history currents");
            this.currents = List.copyOf(currents);
            if (this.currents.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException(
                        "Generation history must not contain null"
                );
            }
        }

        private static History empty() {
            return new History(List.of());
        }

        public static History rehydrate(List<Current> currents) {
            return new History(currents);
        }

        public List<Current> currents() {
            return currents;
        }

        private History append(Current value) {
            List<Current> changed = new ArrayList<>(currents);
            changed.add(Objects.requireNonNull(value, "Generation current"));
            return new History(changed);
        }
    }

    private static String requireText(String value, String field) {
        return RequiredUtil.required(value, field + " must not be blank")
                .trim();
    }

    private static String normalizeOptionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
