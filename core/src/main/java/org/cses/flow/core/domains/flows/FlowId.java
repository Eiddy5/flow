package org.cses.flow.core.domains.flows;

import org.cses.flow.core.utils.RequiredUtil;

/**
 * Business selector for one logical Flow or one exact deployed version.
 */
public record FlowId(
    String companyId,
    String key,
    Long version
) {

    public FlowId {
        companyId = RequiredUtil.required(
            companyId,
            "FlowId companyId must not be blank"
        ).trim();
        key = RequiredUtil.required(
            key,
            "FlowId key must not be blank"
        ).trim();
        if (version != null && version < 1) {
            throw new IllegalArgumentException(
                "FlowId version must be positive"
            );
        }
    }

    public static FlowId from(String companyId, String key) {
        return new FlowId(companyId, key, null);
    }

    public static FlowId from(
        String companyId,
        String key,
        long version
    ) {
        return new FlowId(companyId, key, version);
    }
}
