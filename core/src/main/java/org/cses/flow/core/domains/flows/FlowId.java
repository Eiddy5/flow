package org.cses.flow.core.domains.flows;

/**
 * Exact business reference to one deployed Flow version.
 */
public record FlowId(
    String companyId,
    String flowKey,
    long flowVersion
) {

    public FlowId {
        companyId = requireText(companyId, "Company id");
        flowKey = requireText(flowKey, "Flow key");
        if (flowVersion < 1) {
            throw new IllegalArgumentException(
                "Flow version must be positive"
            );
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
