package org.flow.core.models.flows;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;

import org.flow.core.models.Label;
import org.flow.core.models.flows.input.StringInput;
import org.flow.core.models.property.Property;
import org.flow.core.serializers.JacksonMapper;
import org.flow.core.utils.IdUtils;
import org.flow.plugin.core.debug.Return;
import org.flow.plugin.core.log.Log;
import org.flow.plugin.core.trigger.Schedule;

import static org.assertj.core.api.Assertions.assertThat;

class FlowWithSourceTest {
    @Test
    void source() throws JsonProcessingException {
        FlowWithSource flow = FlowWithSource.builder()
            .id(IdUtils.create())
            .namespace("org.flow.unittest")
            .tasks(
                List.of(
                    Return.builder()
                        .id(IdUtils.create())
                        .type(Return.class.getName())
                        .format(
                            Property.ofValue(
                                "123456789 \n123456789 123456789 123456789 123456789 123456789 123456789 123456789 123456789 123456789 123456789 123456789 123456789 123456789 123456789\n" +
                                    "123456789 \n" +
                                    "123456789 \n" +
                                    "123456789     \n"
                            )
                        )
                        .build()
                )
            )
            .build();

        flow = flow.toBuilder().source(flow.sourceOrGenerateIfNull()).build();

        String source = flow.getSource();

        assertThat(source).doesNotContain("deleted: false");
        assertThat(source).contains("format: |\n");
    }

    @Test
    void scalar() throws JsonProcessingException {
        FlowWithSource.FlowWithSourceBuilder<?, ?> builder = FlowWithSource.builder()
            .id(IdUtils.create())
            .namespace("org.flow.unittest")
            .tasks(
                List.of(
                    Log.builder()
                        .id(IdUtils.create())
                        .type(Log.class.getName())
                        .message("Hello World")
                        .build()
                )
            )
            .triggers(List.of(Schedule.builder().id("schedule").cron("0 1 9 * * *").build()));

        FlowWithSource flow = builder
            .source(JacksonMapper.ofYaml().writeValueAsString(builder.build()))
            .build();

        String source = flow.getSource();

        assertThat(source).contains("message: Hello World");
        assertThat(source).contains("  cron: 0 1 9 * * *");
    }

    @SuppressWarnings("deprecation")
    @Test
    void of() {
        // test that all fields are transmitted to FlowWithSource
        FlowWithSource flow = FlowWithSource.builder()
            .tenantId("tenantId")
            .id(IdUtils.create())
            .namespace("org.flow.unittest")
            .description("description")
            .labels(
                List.of(
                    new Label("key", "value")
                )
            )
            .inputs(
                List.of(
                    StringInput.builder().id("strInput").build()
                )
            )
            .variables(
                Map.of(
                    "varKey", "varValue"
                )
            )
            .tasks(
                List.of(
                    Log.builder()
                        .id(IdUtils.create())
                        .type(Log.class.getName())
                        .message("Hello World")
                        .build()
                )
            )
            .errors(
                List.of(
                    Log.builder()
                        .id(IdUtils.create())
                        .type(Log.class.getName())
                        .message("Error")
                        .build()
                )
            )
            ._finally(
                List.of(
                    Log.builder()
                        .id(IdUtils.create())
                        .type(Log.class.getName())
                        .message("Finally")
                        .build()
                )
            )
            .triggers(
                List.of(
                    Schedule.builder().id("schedule").cron("0 1 9 * * *").build()
                )
            )
            .concurrency(
                Concurrency.builder()
                    .behavior(Concurrency.Behavior.CANCEL)
                    .limit(2)
                    .build()
            )
            .build();
        String expectedSource = flow.sourceOrGenerateIfNull() + " # additional comment";
        FlowWithSource of = FlowWithSource.of(flow, expectedSource);

        assertThat(of.equalsWithoutRevision(flow)).isTrue();
        assertThat(of.getSource()).isEqualTo(expectedSource);
    }

    @Test
    void toDeletedShouldClearDraft() {
        // A draft revision is skipped by the ranking used to resolve an execution without an
        // explicit revision, so a deleted revision left as a draft would let the previous,
        // still-live revision resurface as executable.
        FlowWithSource flow = FlowWithSource.builder()
            .id(IdUtils.create())
            .namespace("org.flow.unittest")
            .revision(1)
            .draft(true)
            .tasks(
                List.of(
                    Log.builder()
                        .id(IdUtils.create())
                        .type(Log.class.getName())
                        .message("Hello World")
                        .build()
                )
            )
            .source("source")
            .build();

        FlowWithSource deleted = flow.toDeleted();

        assertThat(deleted.isDeleted()).isTrue();
        assertThat(deleted.isDraft()).isFalse();
        assertThat(deleted.getRevision()).isEqualTo(2);
    }

    @Test
    void toFlowShouldPreserveRevisionAndUpdated() {
        Instant updated = Instant.parse("2026-08-06T08:55:00.788514407Z");
        FlowWithSource flowWithSource = FlowWithSource.builder()
            .id(IdUtils.create())
            .namespace("org.flow.unittest")
            .revision(2)
            .updated(updated)
            .tasks(
                List.of(
                    Log.builder()
                        .id(IdUtils.create())
                        .type(Log.class.getName())
                        .message("Hello World")
                        .build()
                )
            )
            .source("source")
            .build();

        Flow flow = flowWithSource.toFlow();

        assertThat(flow.getRevision()).isEqualTo(2);
        assertThat(flow.getUpdated()).isEqualTo(updated);
    }
}