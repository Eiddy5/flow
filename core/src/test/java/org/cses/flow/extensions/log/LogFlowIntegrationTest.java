package org.cses.flow.extensions.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micronaut.context.ApplicationContext;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.services.executions.ExecutionService;
import org.cses.flow.core.services.externaltasks.ExternalTaskService;
import org.cses.flow.core.services.flows.FlowService;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogFlowIntegrationTest {

    @Test
    void rendersDependencyOutputThroughTheExecutorAndWorkerChain() {
        LogCapture logs = LogCapture.start();
        try (logs;
             ApplicationContext context = ApplicationContext.run(Map.of(
                 "flow.memory.enabled", true,
                 "datasources.default.enabled", false,
                 "flyway.datasources.default.enabled", false,
                 "micronaut.config-client.enabled", false,
                 "consul.client.registration.enabled", false,
                 "consul.client.watch.service.enabled", false,
                 "grpc.server.enabled", false,
                 "thrift.server.enabled", false,
                 "pulsar.consumer.enabled", false,
                 "jooq.send-event", false
             ))) {
            FlowService flowService = context.getBean(FlowService.class);
            ExecutionService executionService = context.getBean(
                ExecutionService.class
            );
            ExternalTaskService externalTaskService = context.getBean(
                ExternalTaskService.class
            );
            Session<User> session = session();
            var draft = flowService.saveDraft(session, """
                key: log-integration-flow
                tasks:
                  - key: prepare
                    type: org.cses.flow.extensions.flow.Pause
                    pause:
                      key: create-prepare-request
                      type: org.cses.flow.extensions.tasks.AutomaticTask
                    resume:
                      - key: result
                        type: STRING
                  - key: write-log
                    type: org.cses.flow.extensions.log.Log
                    dependOn:
                      - prepare
                    message: "处理结果：{{ dependOnOutputs.prepare.result }}"
                  - key: observe
                    type: org.cses.flow.extensions.tasks.AutomaticTask
                    dependOn:
                      - write-log
                """);
            Flow flow = flowService.deploy(session, draft.id());

            Execution started = executionService.create(session, flow.id());
            var waiting = externalTaskService.waitingTasks(session)
                .stream()
                .filter(task -> task.executionId().equals(started.id()))
                .findFirst()
                .orElseThrow();
            Execution completed = externalTaskService.complete(
                session,
                waiting.id(),
                Map.of("result", "ready")
            );

            assertEquals(State.Type.SUCCESS, completed.state().current());
            assertEquals(List.of("处理结果：ready"), logs.messages());
            assertTrue(completed.activeTaskRuns().isEmpty());
            assertTrue(externalTaskService.waitingTasks(session).isEmpty());
        }
    }

    private static Session<User> session() {
        User user = new User();
        user.setId("log-user");
        user.setCompanyId("log-company");
        Session<User> session = new Session<>();
        session.setUser(user);
        return session;
    }

    private static final class LogCapture implements AutoCloseable {

        private final ch.qos.logback.classic.Logger logger;
        private final ListAppender<ILoggingEvent> appender;

        private LogCapture(
            ch.qos.logback.classic.Logger logger,
            ListAppender<ILoggingEvent> appender
        ) {
            this.logger = logger;
            this.appender = appender;
        }

        private static LogCapture start() {
            ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
                    Log.class
                );
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            return new LogCapture(logger, appender);
        }

        private List<String> messages() {
            return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
