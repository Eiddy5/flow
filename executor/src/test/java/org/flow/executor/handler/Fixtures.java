package org.flow.executor.handler;

import java.util.List;

import org.flow.core.models.flows.Concurrency;
import org.flow.core.models.flows.Flow;
import org.flow.core.models.flows.quota.Quota;
import org.flow.core.models.flows.sla.SLA;
import org.flow.core.utils.IdUtils;
import org.flow.plugin.core.log.Log;

final class Fixtures {
    private Fixtures() {
        // utility class pattern
    }

    static Flow flow() {
        return Flow.builder()
            .tenantId("tenant")
            .namespace("namespace")
            .id(IdUtils.create())
            .tasks(List.of(Log.builder().id("log").type(Log.class.getName()).message("Hello World").build()))
            .build();
    }

    static Flow flowWithConcurrency(Concurrency concurrency) {
        return Flow.builder()
            .tenantId("tenant")
            .namespace("namespace")
            .id(IdUtils.create())
            .tasks(List.of(Log.builder().id("log").type(Log.class.getName()).message("Hello World").build()))
            .concurrency(concurrency)
            .build();
    }

    static Flow flowWithQuotas(Quota quota) {
        return Flow.builder()
            .tenantId("tenant")
            .namespace("namespace")
            .id(IdUtils.create())
            .tasks(List.of(Log.builder().id("log").type(Log.class.getName()).message("Hello World").build()))
            .quotas(List.of(quota))
            .build();
    }

    static Flow flowWithSla(SLA sla) {
        return Flow.builder()
            .tenantId("tenant")
            .namespace("namespace")
            .id(IdUtils.create())
            .tasks(List.of(Log.builder().id("log").type(Log.class.getName()).message("Hello World").build()))
            .sla(List.of(sla))
            .build();
    }
}
