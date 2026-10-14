
package org.flow.core.services;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import com.google.common.collect.ImmutableMap;

import org.flow.core.exceptions.InternalException;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.conditions.ConditionContext;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.LogEntry;
import org.flow.core.models.flows.Flow;
import org.flow.core.models.triggers.TimeWindow;
import org.flow.core.models.triggers.multipleflows.Condition;
import org.flow.core.models.triggers.multipleflows.MultipleCondition;
import org.flow.core.models.triggers.multipleflows.MultipleConditionWindow;
import org.flow.core.queues.DispatchQueueInterface;
import org.flow.core.runners.RunContext;
import org.flow.core.runners.RunContextFactory;
import org.flow.core.utils.TestsUtils;
import org.flow.plugin.core.trigger.Schedule;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
class ConditionServiceTest {
    @Inject
    private ConditionService conditionService;

    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private DispatchQueueInterface<LogEntry> logQueue;

    @Test
    void conditionContextBuildsCorrectContext() {
        // Given
        Flow flow = TestsUtils.mockFlow();
        Execution execution = TestsUtils.mockExecution(flow, ImmutableMap.of());
        RunContext runContext = runContextFactory.of(flow, execution);

        // When
        ConditionContext conditionContext = conditionService.conditionContext(runContext, flow, execution);

        // Then
        assertThat(conditionContext.getFlow()).isEqualTo(flow);
        assertThat(conditionContext.getExecution()).isEqualTo(execution);
        assertThat(conditionContext.getRunContext()).isEqualTo(runContext);
    }

    @Test
    void isValidTriggerNoConditionsReturnsTrue() {
        // Given
        Flow flow = TestsUtils.mockFlow();
        Execution execution = TestsUtils.mockExecution(flow, ImmutableMap.of());
        RunContext runContext = runContextFactory.of(flow, execution);
        Schedule trigger = Schedule.builder()
            .id("unit")
            .type(Schedule.class.getName())
            .cron("0 0 1 * *")
            .build();

        // When
        boolean valid = conditionService.isValid(trigger, flow, runContext);

        // Then
        assertThat(valid).isTrue();
    }

    @Test
    void isValidTriggerWhenInvalidExpressionReturnsFalseAndLogs() {
        // Given
        List<LogEntry> logs = new CopyOnWriteArrayList<>();
        logQueue.addListener(logs::add);

        Flow flow = TestsUtils.mockFlow();
        Execution execution = TestsUtils.mockExecution(flow, ImmutableMap.of());
        RunContext runContext = runContextFactory.of(flow, execution);
        // Malformed Pebble expression causes IllegalVariableEvaluationException during render
        Schedule trigger = Schedule.builder()
            .id("unit")
            .type(Schedule.class.getName())
            .cron("0 0 1 * *")
            .when("{{ invalid-pebble-expression() }}")
            .build();

        // When
        boolean valid = conditionService.isValid(trigger, flow, runContext);

        // Then
        assertThat(valid).isFalse();
        List<LogEntry> matchingLogs = TestsUtils.awaitLogs(
            logs,
            log -> log.getLevel() == Level.WARN && log.getMessage().contains("Evaluate Condition Failed"),
            1
        );
        assertThat(matchingLogs).hasSize(1);
    }

    @Test
    void isValidTriggerWhenFalseReturnsFalse() {
        // Given
        Flow flow = TestsUtils.mockFlow();
        Execution execution = TestsUtils.mockExecution(flow, ImmutableMap.of());
        RunContext runContext = runContextFactory.of(flow, execution);
        Schedule trigger = Schedule.builder()
            .id("unit")
            .type(Schedule.class.getName())
            .cron("0 0 1 * *")
            .when("false")
            .build();

        // When
        boolean valid = conditionService.isValid(trigger, flow, runContext);

        // Then
        assertThat(valid).isFalse();
    }

    @Test
    void isValidTriggerWhenNullReturnsTrue() {
        // Given
        Flow flow = TestsUtils.mockFlow();
        Execution execution = TestsUtils.mockExecution(flow, ImmutableMap.of());
        RunContext runContext = runContextFactory.of(flow, execution);
        Schedule trigger = Schedule.builder()
            .id("unit")
            .type(Schedule.class.getName())
            .cron("0 0 1 * *")
            .when(null)
            .build();

        // When
        boolean valid = conditionService.isValid(trigger, flow, runContext);

        // Then
        assertThat(valid).isTrue();
    }

    @Test
    void isValidMultipleConditionNullReturnsTrue() {
        // Given
        Flow flow = TestsUtils.mockFlow();
        Execution execution = TestsUtils.mockExecution(flow, ImmutableMap.of());
        RunContext runContext = runContextFactory.of(flow, execution);

        // When
        boolean valid = conditionService.isValid(null, flow, execution, Optional.empty(), runContext);

        // Then
        assertThat(valid).isTrue();
    }

    @Test
    void isValidMultipleConditionNullConditionsMapReturnsTrue() {
        // Given
        Flow flow = TestsUtils.mockFlow();
        Execution execution = TestsUtils.mockExecution(flow, ImmutableMap.of());
        RunContext runContext = runContextFactory.of(flow, execution);
        MultipleCondition multipleCondition = new MultipleCondition() {
            @Override
            public String getId() {
                return "test";
            }

            @Override
            public TimeWindow getTimeWindow() {
                return null;
            }

            @Override
            public Map<String, Condition> getConditions() {
                return null;
            }

            @Override
            public Logger logger() {
                return LoggerFactory.getLogger(ConditionServiceTest.class);
            }

            @Override
            public Mode getMode() {
                return Mode.ALL;
            }

            @Override
            public Integer getMinSatisfied() {
                return null;
            }
        };

        // When
        boolean valid = conditionService.isValid(multipleCondition, flow, execution, Optional.empty(), runContext);

        // Then
        assertThat(valid).isTrue();
    }

    @Test
    void isValidMultipleConditionExceptionReturnsFalseAndLogs() {
        // Given
        List<LogEntry> logs = new CopyOnWriteArrayList<>();
        logQueue.addListener(logs::add);

        Flow flow = TestsUtils.mockFlow();
        Execution execution = TestsUtils.mockExecution(flow, ImmutableMap.of());
        RunContext runContext = runContextFactory.of(flow, execution);
        MultipleCondition throwingCondition = new MultipleCondition() {
            @Override
            public String getId() {
                return "test";
            }

            @Override
            public TimeWindow getTimeWindow() {
                return null;
            }

            @Override
            public Map<String, Condition> getConditions() {
                return Map.of(
                    "condition_1",
                    ignored -> true
                );
            }

            @Override
            public Logger logger() {
                return LoggerFactory.getLogger(ConditionServiceTest.class);
            }

            @Override
            public Mode getMode() {
                return Mode.ALL;
            }

            @Override
            public Integer getMinSatisfied() {
                return null;
            }

            @Override
            public boolean test(ConditionContext conditionContext, Optional<MultipleConditionWindow> multipleConditionWindow) throws InternalException {
                throw new InternalException("simulated evaluation failure");
            }
        };

        // When
        boolean valid = conditionService.isValid(throwingCondition, flow, execution, Optional.empty(), runContext);

        // Then
        assertThat(valid).isFalse();
        List<LogEntry> matchingLogs = TestsUtils.awaitLogs(
            logs,
            log -> log.getLevel() == Level.WARN && log.getMessage().contains("Evaluate Condition Failed"),
            1
        );
        assertThat(matchingLogs).hasSize(1);
    }
}
