package org.flow.scheduler.internals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.flow.core.exceptions.FlowBlockedException;
import org.flow.core.exceptions.FlowProcessingException;
import org.flow.core.models.flows.FlowWithException;
import org.flow.core.models.flows.FlowWithSource;
import org.flow.core.runners.ProcessedFlow;
import org.flow.core.services.FlowParsingService;
import org.flow.plugin.core.trigger.Schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TriggerFlowParserTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(TriggerFlowParserTest.class);

    private final FlowWithSource flow = FlowWithSource.builder()
        .tenantId("main")
        .namespace("org.flow.tests")
        .id("trigger-flow-parser")
        .triggers(List.of(Schedule.builder().id("schedule").type(Schedule.class.getName()).cron("* * * * *").build()))
        .build();

    @Test
    void shouldThrowWithTheReasonWhenBlockedByGovernance() throws FlowProcessingException {
        // Given
        FlowParsingService flowParsingService = mock(FlowParsingService.class);
        when(flowParsingService.parseForRuntime(flow)).thenThrow(new FlowBlockedException("Blocked by governance policy: policy=deny-log"));

        // When / Then the block reaches the caller with its reason, so it can report it against the trigger
        assertThatThrownBy(() -> TriggerFlowParser.parseForTrigger(flowParsingService, flow, LOGGER))
            .isInstanceOf(FlowBlockedException.class)
            .hasMessage("Blocked by governance policy: policy=deny-log");
    }

    @Test
    void shouldDegradeToStoredFlowWhenParsingFails() throws Exception {
        // Given
        FlowParsingService flowParsingService = mock(FlowParsingService.class);
        when(flowParsingService.parseForRuntime(flow)).thenThrow(new FlowProcessingException("invalid"));

        // When / Then a non-governance failure keeps the flow as stored so existing triggers keep evaluating
        assertThat(TriggerFlowParser.parseForTrigger(flowParsingService, flow, LOGGER)).isSameAs(flow);
    }

    @Test
    void shouldThrowWithTheReasonWhenStoredFlowHasNoTriggersAndParsingFails() throws Exception {
        // Given a flow 2.0 could not deserialize: kept as FlowWithException, without its trigger definitions
        FlowWithSource stored = FlowWithException.from(flow, new FlowProcessingException("Invalid type: org.flow.plugin.core.flow.ForEach"));
        FlowParsingService flowParsingService = mock(FlowParsingService.class);
        when(flowParsingService.parseForRuntime(stored)).thenThrow(new FlowProcessingException("Invalid type: org.flow.plugin.core.flow.ForEach"));

        // When / Then there is nothing to degrade to: the caller gets the reason to report against the trigger
        assertThatThrownBy(() -> TriggerFlowParser.parseForTrigger(flowParsingService, stored, LOGGER))
            .isInstanceOf(FlowProcessingException.class)
            .hasMessage("Invalid type: org.flow.plugin.core.flow.ForEach");
    }

    @Test
    void shouldReturnParsedFlowWhenParsingSucceeds() throws Exception {
        // Given
        FlowParsingService flowParsingService = mock(FlowParsingService.class);
        FlowWithSource parsed = flow.toBuilder().revision(2).build();
        when(flowParsingService.parseForRuntime(flow)).thenReturn(ProcessedFlow.of(parsed));

        // When / Then
        assertThat(TriggerFlowParser.parseForTrigger(flowParsingService, flow, LOGGER)).isSameAs(parsed);
    }
}
