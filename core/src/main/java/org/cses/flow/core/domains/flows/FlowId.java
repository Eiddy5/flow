package org.cses.flow.core.domains.flows;

import org.cses.flow.core.domains.Identity;
import org.cses.flow.core.utils.AssertUtil;
import org.paas.common.util.StringUtil;

/**
 * Exact business reference to one deployed Flow version.
 */
public record FlowId(
        String key,
        Long version
) implements Identity {

    public static FlowId from(
            String key,
            Long version
    ) {
        return new FlowId(key, version);
    }

    public FlowId {
        AssertUtil.assertNotBlank(key, "flowId.key is required");
        AssertUtil.assertNotNull(version, "flowId.version is required");
    }


    @Override
    public void valid() {
        AssertUtil.assertNotBlank(key, "flowId.key is required");
        AssertUtil.assertNotNull(version, "flowId.version is required");
    }

    @Override
    public String identifier() {
        return StringUtil.join(key, version);
    }
}
