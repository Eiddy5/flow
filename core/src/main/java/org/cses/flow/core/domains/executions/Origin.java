package org.cses.flow.core.domains.executions;

import org.cses.flow.core.utils.RequiredUtil;

import java.util.Objects;

/** Identifies the direct parent and the first Execution in one derivation tree. */
public class Origin {

    private String parentId;
    private String originId;

    /**
     * Stores validated immutable Execution references.
     * @param parentId direct parent, or null for the first Execution
     * @param originId nonblank first Execution ID
     */
    private Origin(String parentId, String originId) {
        this.parentId = parentId == null ? null : RequiredUtil.required(parentId, "Parent Execution id");
        this.originId = RequiredUtil.required(originId, "Origin Execution id");
    }

    /**
     * Creates a source relationship without generating or changing either identity.
     * @param parentId direct parent, or null for a root
     * @param originId nonblank root Execution ID
     * @return immutable relationship
     * @throws IllegalArgumentException when a supplied ID is blank
     */
    public static Origin create(String parentId, String originId) {
        return new Origin(parentId, originId);
    }

    /** @return direct parent Execution ID, or null for the root */
    public String parentId() {
        return parentId;
    }

    /** @return the first Execution ID shared by the derivation tree */
    public String originId() {
        return originId;
    }

    /**
     * Compares the two source references.
     * @param other relationship or another object, including null
     * @return true when both references match
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof Origin value
                && Objects.equals(parentId, value.parentId) && originId.equals(value.originId);
    }

    /** @return hash of the two source references */
    @Override
    public int hashCode() {
        return Objects.hash(parentId, originId);
    }
}
