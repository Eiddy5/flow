package org.flow.core.junit.extensions;

import java.net.URL;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Objects;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import org.flow.core.junit.annotations.ExecuteFlow;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.Flow;
import org.flow.core.models.flows.FlowWithSource;
import org.flow.core.repositories.FlowRepositoryInterface;
import org.flow.core.repositories.LocalFlowRepositoryLoader;
import org.flow.core.runners.TestRunnerUtils;
import org.flow.core.serializers.YamlParser;
import org.flow.core.services.FlowService;
import org.flow.core.utils.TestsUtils;

import lombok.SneakyThrows;

import static org.flow.core.utils.Rethrow.throwConsumer;

public class FlowExecutorExtension extends AbstractLoaderExtension implements AfterEachCallback, ParameterResolver {

    @Override
    public boolean supportsParameter(ParameterContext parameterContext,
        ExtensionContext extensionContext) throws ParameterResolutionException {
        return parameterContext.getParameter().getType() == Execution.class;
    }

    @SneakyThrows
    @Override
    public Object resolveParameter(ParameterContext parameterContext,
        ExtensionContext extensionContext) throws ParameterResolutionException {
        loadApplicationContext(extensionContext);
        abortIfContextStopped();

        ExecuteFlow executeFlow = getExecuteFlow(extensionContext);
        String tenantId = executeFlow.tenantId();

        String path = executeFlow.value();
        URL url = getClass().getClassLoader().getResource(path);
        if (url == null) {
            throw new IllegalArgumentException("Unable to load flow: " + path);
        }
        createTenant(extensionContext, tenantId);
        LocalFlowRepositoryLoader repositoryLoader = context.getBean(LocalFlowRepositoryLoader.class);
        TestsUtils.loads(tenantId, repositoryLoader, Objects.requireNonNull(url));

        Flow flow = YamlParser.parse(Paths.get(url.toURI()).toFile(), Flow.class);
        TestRunnerUtils runnerUtils = context.getBean(TestRunnerUtils.class);
        return runnerUtils.runOne(tenantId, flow.getNamespace(), flow.getId(), Duration.parse(executeFlow.timeout()), executeFlow.executionKind());
    }

    @Override
    public void afterEach(ExtensionContext extensionContext) throws Exception {
        if (!isContextRunning()) {
            return;
        }

        ExecuteFlow executeFlow = getExecuteFlow(extensionContext);
        FlowRepositoryInterface flowRepository = context.getBean(FlowRepositoryInterface.class);
        FlowService flowService = context.getBean(FlowService.class);

        String path = executeFlow.value();
        Flow loadedFlow = getFlow(path);
        flowRepository.findAllForAllTenants().stream()
            .filter(flow -> Objects.equals(flow.getId(), loadedFlow.getId()))
            .filter(flow -> Objects.equals(flow.getTenantId(), executeFlow.tenantId()))
            .forEach(throwConsumer(flow -> flowService.delete(FlowWithSource.of(flow, "unused"))));

        deleteTenant(executeFlow.tenantId());
    }

    private static ExecuteFlow getExecuteFlow(ExtensionContext extensionContext) {
        return extensionContext.getTestMethod()
            .orElseThrow()
            .getAnnotation(ExecuteFlow.class);
    }
}
