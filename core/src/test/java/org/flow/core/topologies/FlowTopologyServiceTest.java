package org.flow.core.topologies;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.flow.core.models.flows.Flow;
import org.flow.core.models.flows.FlowWithSource;
import org.flow.core.models.flows.State;
import org.flow.core.models.flows.Type;
import org.flow.core.models.flows.input.MultiselectInput;
import org.flow.core.models.flows.input.SelectInput;
import org.flow.core.models.property.Property;
import org.flow.core.models.topologies.FlowRelation;
import org.flow.core.serializers.YamlParser;
import org.flow.core.utils.TestsUtils;
import org.flow.plugin.core.debug.Return;
import org.flow.plugin.core.flow.Parallel;
import org.flow.plugin.core.flow.Subflow;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@MicronautTest
class FlowTopologyServiceTest {

    @Inject
    private FlowTopologyService flowTopologyService;

    @Test
    void flowTask() {
        Flow parent = Flow.builder()
            .namespace("org.flow.ee")
            .id("parent")
            .revision(1)
            .tasks(
                List.of(
                    Parallel.builder()
                        .id("para")
                        .type(Parallel.class.getName())
                        .tasks(
                            List.of(
                                Subflow.builder()
                                    .id("launch")
                                    .type(Subflow.class.getName())
                                    .namespace("org.flow.ee")
                                    .flowId("child")
                                    .build()
                            )
                        )
                        .build()
                )
            )
            .build();

        FlowWithSource child = FlowWithSource.builder()
            .namespace("org.flow.ee")
            .id("child")
            .revision(1)
            .tasks(List.of(returnTask()))
            .build();

        assertThat(flowTopologyService.isChild(parent, child)).isEqualTo(FlowRelation.FLOW_TASK);
    }

    @Test
    void noRelation() {
        Flow parent = Flow.builder()
            .namespace("org.flow.ee")
            .id("parent")
            .revision(1)
            .tasks(List.of(returnTask()))
            .build();

        Flow child = Flow.builder()
            .namespace("org.flow.ee")
            .id("child")
            .revision(1)
            .tasks(List.of(returnTask()))
            .build();

        assertThat(flowTopologyService.isChild(parent, child)).isNull();
    }

    @Test
    void trigger() {
        Flow parent = Flow.builder()
            .namespace("org.flow.ee")
            .id("parent")
            .revision(1)
            .tasks(List.of(returnTask()))
            .build();

        Flow child = Flow.builder()
            .namespace("org.flow.ee")
            .id("child")
            .revision(1)
            .tasks(List.of(returnTask()))
            .triggers(
                List.of(
                    org.flow.plugin.core.trigger.Flow.builder()
                        .type(org.flow.plugin.core.trigger.Flow.class.getName())
                        .dependsOn(
                            List.of(
                                org.flow.plugin.core.trigger.Flow.Dependency.builder()
                                    .namespace("org.flow.ee")
                                    .flowId("parent")
                                    .states(List.of(State.Type.SUCCESS))
                                    .build()
                            )
                        )
                        .build()
                )
            )
            .build();

        assertThat(flowTopologyService.isChild(parent, child)).isEqualTo(FlowRelation.FLOW_TRIGGER);
    }

    @Test
    void dependsOn() {
        Flow parent = Flow.builder()
            .namespace("org.flow.ee")
            .id("parent")
            .revision(1)
            .tasks(List.of(returnTask()))
            .build();

        Flow noTrigger = Flow.builder()
            .namespace("org.flow.exclude")
            .id("no")
            .revision(1)
            .tasks(List.of(returnTask()))
            .build();

        Flow child = Flow.builder()
            .namespace("org.flow.ee")
            .id("child")
            .revision(1)
            .tasks(List.of(returnTask()))
            .triggers(
                List.of(
                    org.flow.plugin.core.trigger.Flow.builder()
                        .type(org.flow.plugin.core.trigger.Flow.class.getName())
                        .dependsOn(
                            List.of(
                                org.flow.plugin.core.trigger.Flow.Dependency.builder().namespace("org.flow.ee").flowId("parent").build(),
                                org.flow.plugin.core.trigger.Flow.Dependency.builder().namespace("org.flow.others").flowId("other").build()
                            )
                        )
                        .build()
                )
            )
            .build();

        assertThat(flowTopologyService.isChild(parent, child)).isEqualTo(FlowRelation.FLOW_TRIGGER);

        assertThat(flowTopologyService.isChild(noTrigger, child)).isNull();
    }

    @Test
    void self() throws IOException {
        Flow flow = parse("flows/valids/trigger-flow-listener.yaml").toBuilder().revision(1).build();
        assertThat(flowTopologyService.isChild(flow, flow)).isNull();
    }

    @Test
    void isChildShouldReturnTrueForSelectInput() {
        Flow parent = Flow.builder()
            .namespace("org.flow.ee")
            .id("parent")
            .revision(1)
            .inputs(
                List.of(
                    SelectInput.builder().id("select").type(Type.SELECT).expression("{{subflow(id='child', namespace='org.flow.ee')}}").build()
                )
            )
            .tasks(List.of(returnTask()))
            .build();

        FlowWithSource child = FlowWithSource.builder()
            .namespace("org.flow.ee")
            .id("child")
            .revision(1)
            .tasks(List.of(returnTask()))
            .build();

        assertThat(flowTopologyService.isChild(parent, child)).isEqualTo(FlowRelation.SUBFLOW_FUNCTION);
    }

    @Test
    void isChildShouldReturnFalseForPartialMatch() {
        Flow parent = Flow.builder()
            .namespace("org.flow.ee")
            .id("parent")
            .revision(1)
            .inputs(
                List.of(
                    SelectInput.builder().id("select").type(Type.SELECT).expression("{{subflow(id='child-longer', namespace='org.flow.ee')}}").build()
                )
            )
            .tasks(List.of(returnTask()))
            .build();

        FlowWithSource child = FlowWithSource.builder()
            .namespace("org.flow.ee")
            .id("child")
            .revision(1)
            .tasks(List.of(returnTask()))
            .build();

        assertThat(flowTopologyService.isChild(parent, child)).isNull();
    }

    @Test
    void isChildShouldReturnTrueForMultiSelectInput() {
        Flow parent = Flow.builder()
            .namespace("org.flow.ee")
            .id("parent")
            .revision(1)
            .inputs(
                List.of(
                    MultiselectInput.builder().id("multiselect").type(Type.MULTISELECT).expression("{{subflow(id='child', namespace='org.flow.ee')}}").build()
                )
            )
            .tasks(List.of(returnTask()))
            .build();

        FlowWithSource child = FlowWithSource.builder()
            .namespace("org.flow.ee")
            .id("child")
            .revision(1)
            .tasks(List.of(returnTask()))
            .build();

        assertThat(flowTopologyService.isChild(parent, child)).isEqualTo(FlowRelation.SUBFLOW_FUNCTION);
    }

    private Return returnTask() {
        return Return.builder()
            .id("return")
            .type(Return.class.getName())
            .format(Property.ofValue("ok"))
            .build();
    }

    private Flow parse(String path) throws IOException {
        URL resource = TestsUtils.class.getClassLoader().getResource(path);
        assert resource != null;

        File file = new File(resource.getFile());

        return YamlParser.parse(Files.readString(file.toPath()), Flow.class);
    }
}