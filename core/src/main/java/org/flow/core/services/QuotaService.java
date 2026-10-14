package org.flow.core.services;

import java.util.Optional;

import org.flow.core.models.flows.FlowInterface;
import org.flow.core.models.flows.quota.Quota;
import org.flow.core.utils.ListUtils;

import jakarta.inject.Singleton;

/**
 * Service to manage quotas.
 * As Quota is an EE feature, this service throws unconditionally.
 */
@Singleton
public class QuotaService {
    /**
     * Check and increment all quotas for the given flow.
     * When a quota is exceeded, all other quotas are not incremented and the quota is returned.
     *
     * @return the quota that was exceeded if any
     * @implNote Quotas are an EE feature, on OSS this service throws unconditionally if the flow has quotas.
     */
    public Optional<Quota> checkAndIncrement(FlowInterface flow) {
        if (!ListUtils.isEmpty(flow.getQuotas())) {
            throw new UnsupportedOperationException("Quotas are an EE feature");
        }

        return Optional.empty();
    }
}
