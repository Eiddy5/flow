package org.flow.worker.services;

import java.io.IOException;
import org.flow.core.exceptions.DeserializationException;
import org.flow.core.models.executions.TaskRun;
import org.flow.core.models.triggers.TriggerId;
import org.flow.core.queues.DispatchQueueInterface;
import org.flow.core.queues.QueueException;
import org.flow.core.runners.WorkerTaskResult;
import org.flow.core.scheduler.events.TriggerEvaluated;
import org.flow.core.scheduler.queue.TriggerEventQueue;
import org.flow.core.serializers.JacksonMapper;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/** Converts unreadable plugin jobs into failure results inside the claim transaction. */
@Singleton
@Slf4j
public class WorkerJobErrorHandler {
    private final DispatchQueueInterface<WorkerTaskResult> results;
    private final TriggerEventQueue triggers;

    public WorkerJobErrorHandler(DispatchQueueInterface<WorkerTaskResult> results, TriggerEventQueue triggers) {
        this.results = results;
        this.triggers = triggers;
    }

    public void handle(DeserializationException error) {
        log.error("Cannot deserialize worker job", error);
        if (error.getRecord() == null) {
            return;
        }
        try {
            var mapper = JacksonMapper.ofJson();
            var job = mapper.readTree(error.getRecord()).path("job");
            if ("task".equals(job.path("type").asText())) {
                results.emit(new WorkerTaskResult(mapper.treeToValue(job.path("taskRun"), TaskRun.class).fail()));
            } else if ("trigger".equals(job.path("type").asText())) {
                var data = job.has("data") ? job.path("data") : job.path("triggerContext");
                String triggerId = data.has("triggerId") ? data.path("triggerId").asText() : job.path("trigger").path("id").asText();
                triggers.send(new TriggerEvaluated(TriggerId.of(data.path("tenantId").asText(null),
                    data.path("namespace").asText(), data.path("flowId").asText(), triggerId), null));
            }
        } catch (IOException e) {
            log.error("Unable to recover the identity of an unreadable worker job", e);
        } catch (QueueException e) {
            // Failure publication must commit before the bad input can be acknowledged.
            throw new IllegalStateException("Unable to publish deserialization failure", e);
        }
    }
}
