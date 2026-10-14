package org.flow.core.junit.extensions;

import java.net.URL;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.util.Optional;

import org.junit.jupiter.api.extension.*;

import org.flow.core.junit.annotations.EvaluateTrigger;
import org.flow.core.models.conditions.ConditionContext;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.Flow;
import org.flow.core.models.triggers.AbstractTrigger;
import org.flow.core.models.triggers.PollingTriggerInterface;
import org.flow.core.models.triggers.TriggerContext;
import org.flow.core.runners.DefaultRunContext;
import org.flow.core.runners.RunContextFactory;
import org.flow.core.runners.RunContextInitializer;
import org.flow.core.serializers.YamlParser;

import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.MicronautJunit5Extension;
import lombok.SneakyThrows;

import static org.flow.core.tenant.TenantService.MAIN_TENANT;

/**
 * JUnit 5 extension to evaluate triggers and inject its Optional<Execution>.
 */
public class TriggerEvaluationExtension implements ParameterResolver {
    ApplicationContext context;

    RunContextFactory runContextFactory;

    RunContextInitializer runContextInitializer;

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == Optional.class;
    }

    @SneakyThrows
    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        ensureContext(extensionContext);

        EvaluateTrigger evaluateTrigger = extensionContext.getRequiredTestMethod()
            .getAnnotation(EvaluateTrigger.class);

        String path = evaluateTrigger.flow();
        URL url = getClass().getClassLoader().getResource(path);
        if (url == null) {
            throw new IllegalArgumentException("Unable to load flow: " + path);
        }

        Flow flow = YamlParser.parse(Paths.get(url.toURI()).toFile(), Flow.class);

        if (flow.getTenantId() == null) {
            flow = flow.toBuilder().tenantId(MAIN_TENANT).build();
        }

        AbstractTrigger trigger = flow.getTriggers().stream()
            .filter(t -> t.getId().equals(evaluateTrigger.triggerId()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Trigger not found: " + evaluateTrigger.triggerId()));

        // Re-checked because parsing the flow above is slow enough for a shutdown to land in between, and
        // evaluating the trigger initialises a run context, which resolves @ConfigurationProperties beans.
        ExtensionUtils.abortIfContextStopped(context);

        return evaluateTrigger(trigger, flow);
    }

    private void ensureContext(ExtensionContext extensionContext) {
        if (context == null) {
            // Try FlowTestExtension namespace (used by @FlowTest)
            context = extensionContext.getRoot()
                .getStore(ExtensionContext.Namespace.create(FlowTestExtension.class, extensionContext.getTestClass().get()))
                .get(ApplicationContext.class, ApplicationContext.class);

            // Fallback to MicronautJunit5Extension namespace (used by @MicronautTest)
            if (context == null) {
                context = extensionContext.getRoot()
                    .getStore(ExtensionContext.Namespace.create(MicronautJunit5Extension.class))
                    .get(ApplicationContext.class, ApplicationContext.class);
            }

            if (context == null) {
                throw new IllegalStateException("No ApplicationContext found. Add @FlowTest or @MicronautTest to the test class.");
            }
        }

        // Checked on every resolution rather than only when the context is first looked up: one instance
        // serves every invocation of a @TestTemplate and every Optional parameter of a method.
        ExtensionUtils.abortIfContextStopped(context);

        if (runContextFactory == null || runContextInitializer == null) {
            // Published together, so failing to resolve the second cannot leave the extension
            // half-initialised and never retried.
            RunContextFactory factory = context.getBean(RunContextFactory.class);
            RunContextInitializer initializer = context.getBean(RunContextInitializer.class);
            runContextFactory = factory;
            runContextInitializer = initializer;
        }
    }

    private Optional<Execution> evaluateTrigger(AbstractTrigger trigger, Flow flow) throws Exception {

        if (trigger instanceof PollingTriggerInterface pollingTrigger) {
            TriggerContext triggerContext = triggerContext(trigger, flow);
            ConditionContext conditionContext = conditionContext(trigger, flow);

            return pollingTrigger.eval(conditionContext, triggerContext).map(eval -> eval.toExecution(triggerContext));
        } else {
            throw new IllegalArgumentException("Unsupported trigger type: " + trigger.getClass());
        }
    }

    private ConditionContext conditionContext(AbstractTrigger trigger, Flow flow) {

        TriggerContext triggerContext = triggerContext(trigger, flow);

        return ConditionContext.builder()
            .runContext(
                runContextInitializer.forScheduler(
                    (DefaultRunContext) runContextFactory.of(flow, trigger), triggerContext, trigger
                )
            )
            .flow(flow)
            .build();
    }

    private TriggerContext triggerContext(AbstractTrigger trigger, Flow flow) {
        return TriggerContext.builder()
            .namespace(flow.getNamespace())
            .flowId(flow.getId())
            .triggerId(trigger.getId())
            .date(ZonedDateTime.now())
            .tenantId(flow.getTenantId())
            .build();
    }

}
