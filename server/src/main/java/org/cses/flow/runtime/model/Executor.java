package org.cses.flow.runtime.model;

import java.time.Instant;
import java.util.Objects;
import org.cses.flow.definition.model.Node;

public final class Executor {

    private final String id;
    private final String processId;
    private final String parentId;
    private final Instant createdAt;
    private String currentNodeId;
    private ExecutorState state;
    private Instant updatedAt;

    Executor(String id, String processId, String parentId, Node startNode) {
        this.id = Objects.requireNonNull(id, "id");
        this.processId = Objects.requireNonNull(processId, "processId");
        this.parentId = parentId;
        this.currentNodeId = Objects.requireNonNull(startNode, "startNode").id();
        this.state = ExecutorState.ACTIVE;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    private Executor(Executor source) {
        this.id = source.id;
        this.processId = source.processId;
        this.parentId = source.parentId;
        this.currentNodeId = source.currentNodeId;
        this.state = source.state;
        this.createdAt = source.createdAt;
        this.updatedAt = source.updatedAt;
    }

    Executor copy() {
        return new Executor(this);
    }

    public void moveTo(Node node) {
        requireState(ExecutorState.ACTIVE);
        currentNodeId = Objects.requireNonNull(node, "node").id();
        updatedAt = Instant.now();
    }

    public void waitForExternalInput() {
        requireState(ExecutorState.ACTIVE);
        state = ExecutorState.WAITING;
        updatedAt = Instant.now();
    }

    public void activate() {
        requireState(ExecutorState.WAITING);
        state = ExecutorState.ACTIVE;
        updatedAt = Instant.now();
    }

    public void complete() {
        requireState(ExecutorState.ACTIVE);
        state = ExecutorState.COMPLETED;
        updatedAt = Instant.now();
    }

    private void requireState(ExecutorState expected) {
        if (state != expected) {
            throw new IllegalStateException(
                    "Executor " + id + " must be " + expected + " but was " + state);
        }
    }

    public String id() {
        return id;
    }

    public String processId() {
        return processId;
    }

    public String parentId() {
        return parentId;
    }

    public String currentNodeId() {
        return currentNodeId;
    }

    public ExecutorState state() {
        return state;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
