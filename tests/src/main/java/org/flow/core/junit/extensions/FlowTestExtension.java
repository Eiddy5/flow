package org.flow.core.junit.extensions;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.runners.TestRunner;
import org.flow.core.utils.TestsUtils;

import io.micronaut.test.annotation.MicronautTestValue;
import io.micronaut.test.extensions.junit5.MicronautJunit5Extension;

public class FlowTestExtension extends MicronautJunit5Extension {
    @Override
    protected MicronautTestValue buildMicronautTestValue(Class<?> testClass) {
        return AnnotationSupport
            .findAnnotation(testClass, FlowTest.class)
            .map(flowTestAnnotation ->
            {
                // "test" first so DB-specific configs override test defaults
                var envs = new java.util.ArrayList<String>();
                envs.add("test");
                for (String env : flowTestAnnotation.environments()) {
                    if (!"test".equals(env)) {
                        envs.add(env);
                    }
                }
                return new MicronautTestValue(
                    flowTestAnnotation.application(),
                    envs.toArray(new String[0]),
                    flowTestAnnotation.packages(),
                    flowTestAnnotation.propertySources(),
                    flowTestAnnotation.rollback(),
                    flowTestAnnotation.transactional(),
                    flowTestAnnotation.rebuildContext(),
                    flowTestAnnotation.contextBuilder(),
                    flowTestAnnotation.transactionMode(),
                    flowTestAnnotation.startApplication(),
                    flowTestAnnotation.resolveParameters()
                );
            })
            .orElse(null);
    }

    @Override
    protected ExtensionContext.Store getStore(ExtensionContext context) {
        return context.getRoot().getStore(ExtensionContext.Namespace.create(FlowTestExtension.class, context.getTestClass().get()));
    }

    @Override
    protected boolean hasExpectedAnnotations(Class<?> testClass) {
        return AnnotationSupport.isAnnotated(testClass, FlowTest.class);
    }

    @Override
    public void beforeAll(ExtensionContext extensionContext) throws Exception {
        super.beforeAll(extensionContext);
        FlowTest flowTest = extensionContext.getTestClass()
            .orElseThrow()
            .getAnnotation(FlowTest.class);
        if (flowTest.startRunner()) {
            TestRunner runner = applicationContext.getBean(TestRunner.class);
            if (!runner.isRunning()) {
                runner.setSchedulerEnabled(flowTest.startScheduler());
                runner.setWorkerEnabled(flowTest.startWorker());
                runner.setSystemWorkerEnabled(flowTest.startSystemWorker());
                runner.run();
            }
        }
    }

    @Override
    public void afterTestExecution(ExtensionContext context) throws Exception {
        super.afterTestExecution(context);

        TestsUtils.queueConsumersCleanup();
    }
}
