package org.flow.integration;

import org.flow.core.runners.FlowEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.flow.core.executor.command.Create;
import org.flow.core.executor.command.ExecutionCommand;
import org.flow.core.models.flows.GenericFlow;
import org.flow.core.queues.DispatchQueueInterface;
import org.flow.core.services.FlowService;
import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;

/** 独立 JVM 运行真实 Worker，父进程在任务运行中强杀它。 */
public class WorkerCrashProbe {
    public static Map<String, Object> properties(String storage) {
        var properties = new HashMap<String, Object>(new FlowJdbcTest().getProperties());
        properties.put("datasources.flow.schema", "flow_worker_crash");
        properties.put("datasources.flow.maximum-pool-size", "24");
        properties.put("flow.server-type", "STANDALONE");
        properties.put("flow.storage.type", "local");
        properties.put("flow.storage.local.base-path", storage);
        properties.put("flow.allocated-cpu-cores", "2");
        properties.put("flow.engine.worker-threads", "1");
        properties.put("flow.server.termination-grace-period", "PT2S");
        properties.put("flow.server.liveness.interval", "PT1S");
        properties.put("flow.server.liveness.timeout", "PT3S");
        properties.put("flow.server.liveness.initial-delay", "PT2S");
        properties.put("flow.server.liveness.heartbeat-interval", "PT1S");
        properties.put("flow.server.service.purge.initial-delay", "PT1H");
        properties.put("flow.server.service.purge.fixed-delay", "P1D");
        return properties;
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        try (var context = ApplicationContext.run(properties(args[0]), "test")) {
            context.getBean(FlowEngine.class).start();
            var flow = context.getBean(FlowService.class).create(GenericFlow.fromYaml("main", """
                id: worker_crash
                namespace: migration.acceptance
                tasks:
                  - id: sleeping
                    type: org.flow.plugin.core.flow.Sleep
                    duration: PT10S
                """));
            var command = Create.of(flow);
            context.getBean(DispatchQueueInterface.class, Qualifiers.byTypeArguments(ExecutionCommand.class)).emit(command);
            Files.writeString(Path.of(args[1]), command.executionId());
            new CountDownLatch(1).await();
        }
    }
}
