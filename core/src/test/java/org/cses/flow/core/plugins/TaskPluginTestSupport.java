package org.cses.flow.core.plugins;

import io.micronaut.validation.validator.Validator;
import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.serializers.FlowDefinitionDeserializer;
import org.cses.flow.core.serializers.JacksonMapper;
import org.cses.flow.core.serializers.YamlParser;
import org.cses.flow.core.validations.ModelValidator;
import org.cses.flow.extensions.tasks.AutomaticTask;
import org.cses.flow.extensions.flow.Loop;
import org.cses.flow.extensions.flow.LoopUntil;
import org.cses.flow.extensions.flow.Pause;
import org.cses.flow.extensions.log.Log;
import org.cses.flow.extensions.flow.Parallel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the production plugin serialization chain without Micronaut.
 */
public final class TaskPluginTestSupport {

    private static final Validator VALIDATOR = Validator.getInstance();

    private TaskPluginTestSupport() {
    }

    public static Context builtInContext(Plugin... additionalPlugins) {
        List<Plugin> plugins = new ArrayList<>();
        plugins.add(new AutomaticTask());
        plugins.add(new Loop());
        plugins.add(new LoopUntil());
        plugins.add(new Pause());
        plugins.add(new Parallel());
        plugins.add(new Log());
        plugins.addAll(List.of(additionalPlugins));

        PluginRegistry registry = new DefaultPluginRegistry(plugins);
        ModelValidator validator = new ModelValidator(VALIDATOR);
        PluginModule module = new PluginModule(registry);
        JacksonMapper mapper = new JacksonMapper(module);
        return Context.from(
            mapper,
            new FlowDefinitionDeserializer(
                new YamlParser(mapper),
                mapper,
                validator
            ),
            validator
        );
    }

    public record Context(
        JacksonMapper jacksonMapper,
        FlowDefinitionDeserializer flowDeserializer,
        ModelValidator modelValidator
    ) {

        public static Context from(
            JacksonMapper jacksonMapper,
            FlowDefinitionDeserializer flowDeserializer,
            ModelValidator modelValidator
        ) {
            return new Context(
                jacksonMapper,
                flowDeserializer,
                modelValidator
            );
        }

        public Flow deploy(
            String companyId,
            String flowId,
            Map<String, ?> definition,
            Flow latest,
            ActorRef actor,
            long deployedAt
        ) {
            return flowDeserializer.deserialize(
                jacksonMapper.writeYaml(definition),
                companyId,
                flowId,
                latest,
                actor,
                deployedAt
            );
        }
    }
}
