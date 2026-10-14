package org.flow.scheduler.endpoint;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.flow.core.models.triggers.AbstractTrigger;
import org.flow.scheduler.DefaultScheduler;
import org.flow.scheduler.internals.DefaultSchedulableTriggerFetcher;
import org.flow.scheduler.models.TriggerEvaluationContext;

import io.micronaut.context.annotation.Requires;
import io.micronaut.management.endpoint.annotation.Endpoint;
import io.micronaut.management.endpoint.annotation.Read;
import jakarta.inject.Inject;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Endpoint(id = "scheduler")
@Requires(property = "flow.server-type", pattern = "(SCHEDULER|STANDALONE)")
public class SchedulerEndpoint {

    private final DefaultScheduler scheduler;
    private final DefaultSchedulableTriggerFetcher schedulableTriggerFetcher;

    @Inject
    public SchedulerEndpoint(DefaultScheduler scheduler, DefaultSchedulableTriggerFetcher schedulableTriggerFetcher) {
        this.scheduler = scheduler;
        this.schedulableTriggerFetcher = schedulableTriggerFetcher;
    }

    @Read
    public SchedulerEndpointResult running() {
        ZonedDateTime zoneScheduleTime = ZonedDateTime.ofInstant(scheduler.clock().instant(), scheduler.clock().getZone());
        List<TriggerEvaluationContext> schedulableTriggers = schedulableTriggerFetcher.getSchedulableTriggers(scheduler.clock(), zoneScheduleTime, scheduler.currentVNodesAssignment());

        List<SchedulerEndpointSchedule> result = schedulableTriggers
            .stream()
            .map(
                context -> new SchedulerEndpointSchedule(
                    context.flow().getId(),
                    context.flow().getNamespace(),
                    context.flow().getRevision(),
                    context.trigger(),
                    context.triggerState().getNextEvaluationDate().atZone(ZoneId.systemDefault())
                )
            )
            .toList();

        return SchedulerEndpointResult.builder()
            .schedulableCount(result.size())
            .schedulable(result)
            .build();
    }

    @Getter
    @Builder
    public static class SchedulerEndpointResult {
        private final int schedulableCount;
        private final List<SchedulerEndpointSchedule> schedulable;
    }

    @Getter
    @AllArgsConstructor
    public static class SchedulerEndpointSchedule {
        private final String flowId;
        private final String namespace;
        private final Integer revision;
        private final AbstractTrigger trigger;
        private final ZonedDateTime next;
    }
}
