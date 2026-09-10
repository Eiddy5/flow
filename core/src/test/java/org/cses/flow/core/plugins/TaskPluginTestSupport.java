package org.cses.flow.core.plugins;

import io.micronaut.validation.validator.Validator;
import jakarta.validation.ConstraintViolationException;
import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.exceptions.WorkflowException;
import org.cses.flow.core.serializers.JacksonMapper;
import org.cses.flow.core.serializers.YamlParser;
import org.cses.flow.core.validations.ModelValidator;
import org.cses.flow.extensions.log.Log;
import org.cses.flow.extensions.flow.Loop;
import org.cses.flow.extensions.flow.LoopUntil;
import org.cses.flow.extensions.flow.Pause;
import org.cses.flow.extensions.flow.Parallel;
import org.cses.flow.extensions.flow.Route;
import org.cses.flow.extensions.flow.Sequence;
import org.cses.flow.extensions.flow.SubFlow;
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

    /**
     * 建立内置及传入插件的测试绑定环境，并初始化 PAAS 结果序列化。
     * @param additionalPlugins 额外只读插件实例；不得为空
     * @return 可发布测试定义的上下文
     */
    public static Context builtInContext(Plugin... additionalPlugins) {
        org.paas.json.JsonFactory.instance = io.micronaut.json.JsonMapper.createDefault();
        List<Plugin> plugins = new ArrayList<>();
        plugins.add(new Log());
        plugins.add(new Loop());
        plugins.add(new LoopUntil());
        plugins.add(new Pause());
        plugins.add(new Parallel());
        plugins.add(new Route());
        plugins.add(new Sequence());
        plugins.add(new SubFlow());
        plugins.add(new TestOutputTasks.Decision());
        plugins.add(new TestOutputTasks.Status());
        plugins.add(new TestOutputTasks.Approved());
        plugins.add(new TestOutputTasks.BooleanApproved());
        plugins.add(new TestOutputTasks.Count());
        plugins.add(new TestOutputTasks.Payload());
        plugins.add(new TestOutputTasks.Result());
        plugins.add(new TestOutputTasks.Prepared());
        plugins.add(new TestOutputTasks.NumericStatus());
        plugins.add(new TestOutputTasks.BranchResult());
        plugins.addAll(List.of(additionalPlugins));

        PluginRegistry registry = new DefaultPluginRegistry(plugins);
        ModelValidator validator = new ModelValidator(VALIDATOR);
        PluginModule module = new PluginModule(registry);
        JacksonMapper mapper = new JacksonMapper(module);
        return Context.from(
            mapper,
            validator
        );
    }

    public record Context(
        JacksonMapper jacksonMapper,
        ModelValidator modelValidator
    ) {

        public static Context from(
            JacksonMapper jacksonMapper,
            ModelValidator modelValidator
        ) {
            return new Context(
                jacksonMapper,
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

        /**
         * Parses and validates a deployed Flow, then returns a persisted-style
         * fixture with the next synthetic version.
         *
         * @param source non-blank raw Flow source
         * @param flowKey fallback Flow key; may be {@code null} when source or
         *        latest provides a key
         * @param latest latest deployed fixture read without modification, or
         *        {@code null} for version one
         * @param session non-null tenant and actor for the fixture
         * @return detached deployed Flow with a positive synthetic test
         *         version and shallow-copied definition containers
         * @throws IllegalArgumentException when source or Task definitions
         *         cannot be parsed or validated
         * @throws WorkflowException when deployed Flow invariants fail
         */
        public Flow deploy(
            String source,
            String flowKey,
            Flow latest,
            Session<User> session
        ) {
            Flow flow = YamlParser.parse(source, Flow.class);
            String fallback = flow.key() == null || flow.key().isBlank()
                ? latest != null
                    ? latest.key()
                    : flowKey == null || flowKey.isBlank()
                        ? StringUtil.newId()
                        : flowKey
                : null;
            flow.resolveKey(fallback);
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
            long version = latest == null ? 1 : latest.version() + 1;
            return Flow.rehydrate(
                flow.id(),
                flow.companyId(),
                flow.key(),
                false,
                version,
                flow.description(),
                flow.variables(),
                flow.inputs(),
                flow.outputs(),
                flow.tasks(),
                flow.status(),
                flow.creator(),
                flow.updater(),
                null,
                flow.createdAt(),
                flow.updatedAt(),
                null,
                flow.source()
            );
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
