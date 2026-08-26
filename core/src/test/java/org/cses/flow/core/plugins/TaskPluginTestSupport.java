package org.cses.flow.core.plugins;

import io.micronaut.validation.validator.Validator;
import jakarta.validation.ConstraintViolationException;
import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.serializers.JacksonMapper;
import org.cses.flow.core.serializers.YamlParser;
import org.cses.flow.core.validations.ModelValidator;
import org.cses.flow.extensions.tasks.AutomaticTask;
import org.cses.flow.extensions.flow.Loop;
import org.cses.flow.extensions.flow.LoopUntil;
import org.cses.flow.extensions.flow.Pause;
import org.cses.flow.extensions.log.Log;
import org.cses.flow.extensions.flow.Parallel;
import org.paas.common.util.StringUtil;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the production plugin serialization chain without Micronaut.
 */
public class TaskPluginTestSupport {

    private static Validator VALIDATOR = Validator.getInstance();

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
            new YamlParser(mapper),
            validator
        );
    }

    public record Context(
        JacksonMapper jacksonMapper,
        YamlParser yamlParser,
        ModelValidator modelValidator
    ) {

        public static Context from(
            JacksonMapper jacksonMapper,
            YamlParser yamlParser,
            ModelValidator modelValidator
        ) {
            return new Context(
                jacksonMapper,
                yamlParser,
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
            String source = jacksonMapper.writeYaml(definition);
            return deploy(
                source,
                flowId,
                latest,
                session(companyId, actor)
            );
        }

        public Flow deploy(
            String source,
            String flowKey,
            Flow latest,
            Session<User> session
        ) {
            Flow flow = yamlParser.parse(source, Flow.class);
            String fallback = flow.key() == null || flow.key().isBlank()
                ? latest != null
                    ? latest.key()
                    : flowKey == null || flowKey.isBlank()
                        ? StringUtil.newId()
                        : flowKey
                : null;
            flow.resolveKey(fallback);
            flow.inputs().forEach(input -> input.validateDefinition());
            flow.tasks().forEach(task -> {
                try {
                    modelValidator.validate(task);
                } catch (ConstraintViolationException exception) {
                    throw new IllegalArgumentException(
                        exception.getMessage(),
                        exception
                    );
                }
            });
            flow.initialize(session, false, latest, source);
            return flow;
        }

        private static Session<User> session(
            String companyId,
            ActorRef actor
        ) {
            User user = new User();
            user.setId(actor.id());
            user.setName(actor.name().orElse(null));
            user.setUserName(actor.name().orElse(null));
            user.setCompanyId(companyId);

            Session<User> session = new Session<>();
            session.setId("session-" + actor.id());
            session.setCompanyId(companyId);
            session.setUser(user);
            return session;
        }
    }
}
