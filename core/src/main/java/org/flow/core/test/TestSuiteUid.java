package org.flow.core.test;

import org.flow.core.utils.IdUtils;

public record TestSuiteUid(String tenant, String namespace, String id) {
    @Override
    public String toString() {
        return IdUtils.fromParts(tenant, namespace, id);
    }
}
