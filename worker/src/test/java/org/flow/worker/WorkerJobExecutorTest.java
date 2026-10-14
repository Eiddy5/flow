package org.flow.worker;

import org.junit.jupiter.api.Test;

import org.flow.core.models.triggers.AbstractTrigger;
import org.flow.core.models.triggers.RealtimeTriggerInterface;
import org.flow.core.runners.WorkerTask;
import org.flow.core.runners.WorkerTrigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class WorkerJobExecutorTest {

    @Test
    void shouldIdentifyRealtimeTriggerJobWhenTriggerIsRealtime() {
        // Given a worker trigger whose trigger is a realtime (streaming) trigger
        WorkerTrigger realtime = mock(WorkerTrigger.class);
        AbstractTrigger trigger = mock(AbstractTrigger.class, withSettings().extraInterfaces(RealtimeTriggerInterface.class));
        when(realtime.getTrigger()).thenReturn(trigger);

        // When-Then
        assertThat(WorkerJobExecutor.isRealtimeTriggerJob(realtime)).isTrue();
    }

    @Test
    void shouldNotIdentifyPollingTriggerOrTaskAsRealtime() {
        // Given a polling trigger and a task
        WorkerTrigger polling = mock(WorkerTrigger.class);
        when(polling.getTrigger()).thenReturn(mock(AbstractTrigger.class));
        WorkerTask task = mock(WorkerTask.class);

        // When-Then
        assertThat(WorkerJobExecutor.isRealtimeTriggerJob(polling)).isFalse();
        assertThat(WorkerJobExecutor.isRealtimeTriggerJob(task)).isFalse();
    }
}
