package org.flow.core.plugins;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import org.flow.core.docs.JsonSchemaGenerator;
import org.flow.core.junit.annotations.ExecuteFlow;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.annotations.Plugin;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.property.Property;
import org.flow.core.models.tasks.RunnableTask;
import org.flow.core.models.tasks.Task;
import org.flow.core.plugins.serdes.PluginDeserializer;
import org.flow.core.runners.RunContext;
import org.flow.core.services.TaskOutputService;

import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest(startRunner = true)
class AdditionalPluginTest {

    @Inject
    private JsonSchemaGenerator jsonSchemaGenerator;

    @Inject
    private TaskOutputService taskOutputService;

    @Test
    @ExecuteFlow("flows/valids/additional-plugin.yaml")
    void additionalPlugin(Execution execution) throws Exception {
        assertThat(execution).isNotNull();
        assertThat(execution.getState().isSuccess()).isTrue();
        assertThat(execution.getTaskRunList()).hasSize(2);
        assertThat(taskOutputService.getOutputs(execution.getTaskRunList().getFirst()).get("output")).isEqualTo("1 -> Hello");
        assertThat(taskOutputService.getOutputs(execution.getTaskRunList().get(1)).get("output")).isEqualTo("Hello World!");
    }

    @Test
    void shouldResolveAdditionalPluginSubtypes() {
        Map<String, Object> generate = jsonSchemaGenerator.properties(null, AdditionalPluginTest.AdditionalPluginTestTask.class);
        var definitions = (Map<String, Map<String, Object>>) generate.get("$defs");
        // the assets declaration of the task base counts the custom asset, the free-form branch of assets.outputs
        assertThat(definitions).hasSize(11);
        assertThat(definitions).containsKey("org.flow.core.plugins.AdditionalPluginTest-AdditionalPluginTest1");
        assertThat(definitions).containsKey("org.flow.core.plugins.AdditionalPluginTest-AdditionalPluginTest2");
    }

    @SuperBuilder
    @ToString
    @EqualsAndHashCode
    @Getter
    @NoArgsConstructor
    @Plugin
    public static class AdditionalPluginTestTask extends Task implements RunnableTask<AdditionalPluginTestTask.Output> {
        @NotNull
        private BaseAdditionalPluginTest additionalPlugin;

        @Override
        public AdditionalPluginTestTask.Output run(RunContext runContext) throws Exception {
            return Output.builder()
                .output(additionalPlugin.sayHello())
                .build();
        }

        @Builder
        @Getter
        public static class Output implements org.flow.core.models.tasks.Output {
            private String output;
        }
    }

    @Plugin
    @SuperBuilder(toBuilder = true)
    @Getter
    @NoArgsConstructor
    // IMPORTANT: The abstract plugin base class must define using the PluginDeserializer,
    // AND concrete subclasses must be annotated by @JsonDeserialize() to avoid StackOverflow.
    @JsonDeserialize(using = PluginDeserializer.class)
    public static abstract class BaseAdditionalPluginTest extends AdditionalPlugin {
        public abstract String sayHello();
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Plugin
    @JsonDeserialize
    public static class AdditionalPluginTest1 extends BaseAdditionalPluginTest {
        @NotNull
        private Property<String> baseMessage;

        @Override
        public String sayHello() {
            return "1 -> " + baseMessage;
        }
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @Plugin
    @JsonDeserialize
    public static class AdditionalPluginTest2 extends BaseAdditionalPluginTest {
        @Override
        public String sayHello() {
            return "Hello World!";
        }
    }
}