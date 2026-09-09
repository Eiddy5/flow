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
public class Generation {

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
        start(null, null, reason, List.of());
    }

    /**
     * Starts a new generation; an existing active generation must first complete.
     *
     * @param sourceTaskRunId source occurrence ID, or null together with target for a loop generation
     * @param targetTaskRunId target occurrence ID, or null together with source
     * @param reason nonblank reason, trimmed before storage
     * @param affectedTaskRunIds non-null unique affected occurrence IDs, copied; empty only for loop rounds
     * @throws WorkflowException when active-generation state does not permit the transition
     */
    public void start(
            String sourceTaskRunId,
            String targetTaskRunId,
            String reason,
            List<String> affectedTaskRunIds
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
                reason,
                affectedTaskRunIds
        );
    }

    public void advance(String reason) {
        advance(null, null, reason, List.of());
    }

    /**
     * Archives the current generation and starts the next version.
     *
     * @param sourceTaskRunId source occurrence ID, or null together with target for a loop generation
     * @param targetTaskRunId target occurrence ID, or null together with source
     * @param reason nonblank reason, trimmed before storage
     * @param affectedTaskRunIds non-null unique affected occurrence IDs, copied; empty only for loop rounds
     * @throws WorkflowException when active-generation state does not permit the transition
     */
    public void advance(
            String sourceTaskRunId,
            String targetTaskRunId,
            String reason,
            List<String> affectedTaskRunIds
    ) {
        requireCurrent();
        Current next = Current.now(
                nextVersion(),
                sourceTaskRunId,
                targetTaskRunId,
                reason,
                affectedTaskRunIds
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

    public static class Current {

        private int version;
        private String sourceTaskRunId;
        private String targetTaskRunId;
        private String reason;
        private long date;
        private List<String> affectedTaskRunIds;

        /**
         * Validates and copies one persisted generation record.
         *
         * @param version positive generation number
         * @param sourceTaskRunId source occurrence ID, or null together with target for a loop generation
         * @param targetTaskRunId target occurrence ID, or null together with source
         * @param reason nonblank reason, trimmed before storage
         * @param date nonnegative creation time in Unix epoch milliseconds
         * @param affectedTaskRunIds non-null unique affected occurrence IDs, copied; empty only for loop rounds
         * @throws IllegalArgumentException when coordinates, reason, version or affected IDs are invalid
         */
        private Current(
                int version,
                String sourceTaskRunId,
                String targetTaskRunId,
                String reason,
                long date,
                List<String> affectedTaskRunIds
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
            this.affectedTaskRunIds = List.copyOf(affectedTaskRunIds);
            if (this.affectedTaskRunIds.stream().anyMatch(id -> id == null || id.isBlank())
                    || this.affectedTaskRunIds.stream().distinct().count() != this.affectedTaskRunIds.size()) {
                throw new IllegalArgumentException("Affected TaskRun IDs must be nonblank and unique");
            }
            if (source != null && (!this.affectedTaskRunIds.contains(source)
                    || !this.affectedTaskRunIds.contains(target))) {
                throw new IllegalArgumentException("Replay affected TaskRun IDs must include both endpoints");
            }
            if (source == null && !this.affectedTaskRunIds.isEmpty()) {
                throw new IllegalArgumentException("Loop generations cannot invalidate Execution TaskRuns");
            }
        }

        /**
         * Creates a validated generation record using the current epoch millisecond timestamp.
         *
         * @param version positive generation number
         * @param sourceTaskRunId source occurrence ID, or null together with target for a loop generation
         * @param targetTaskRunId target occurrence ID, or null together with source
         * @param reason nonblank reason, trimmed before storage
         * @param affectedTaskRunIds non-null unique affected occurrence IDs, copied; empty only for loop rounds
         * @return a new immutable record
         * @throws IllegalArgumentException when coordinates or affected IDs are invalid
         */
        private static Current now(
                int version,
                String sourceTaskRunId,
                String targetTaskRunId,
                String reason,
                List<String> affectedTaskRunIds
        ) {
            return new Current(
                    version,
                    sourceTaskRunId,
                    targetTaskRunId,
                    reason,
                    TimeUtil.now(),
                    affectedTaskRunIds
            );
        }

        /**
         * Restores the exact persisted generation and its copied affected-occurrence set.
         *
         * @param version positive generation number
         * @param sourceTaskRunId source occurrence ID, or null together with target for a loop generation
         * @param targetTaskRunId target occurrence ID, or null together with source
         * @param reason nonblank reason, trimmed before storage
         * @param date nonnegative creation time in Unix epoch milliseconds
         * @param affectedTaskRunIds non-null unique affected occurrence IDs, copied; empty only for loop rounds
         * @return a new validated record
         * @throws IllegalArgumentException when persisted values are invalid
         */
        public static Current rehydrate(
                int version, String sourceTaskRunId, String targetTaskRunId,
                String reason, long date, List<String> affectedTaskRunIds
        ) {
            return new Current(version, sourceTaskRunId, targetTaskRunId, reason, date, affectedTaskRunIds);
        }

        /**
         * Exposes the exact recorded invalidation set without permitting collection mutation.
         *
         * @return the immutable ID list; empty denotes a loop round
         */
        public List<String> affectedTaskRunIds() {
            return affectedTaskRunIds;
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

        /**
         * Compares every persisted generation field including exact invalidations.
         *
         * @param value other value, or null
         * @return true only for equal generation records
         */
        @Override
        public boolean equals(Object value) {
            return this == value
                    || value instanceof Current other
                    && version == other.version
                    && date == other.date
                    && Objects.equals(sourceTaskRunId, other.sourceTaskRunId)
                    && Objects.equals(targetTaskRunId, other.targetTaskRunId)
                    && reason.equals(other.reason)
                    && affectedTaskRunIds.equals(other.affectedTaskRunIds);
        }

        /**
         * Hashes every persisted field used by equality.
         *
         * @return hash consistent with the full generation record
         */
        @Override
        public int hashCode() {
            return Objects.hash(
                    version,
                    sourceTaskRunId,
                    targetTaskRunId,
                    reason,
                    date,
                    affectedTaskRunIds
            );
        }
    }

    public static class History {

        private List<Current> currents;

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
