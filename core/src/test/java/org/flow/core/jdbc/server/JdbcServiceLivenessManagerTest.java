package org.flow.core.jdbc.server;

import org.flow.core.server.LocalServiceStateFactory;
import org.flow.core.server.ServerConfig;
import org.flow.core.server.ServerInstanceFactory;
import org.flow.core.server.Service;
import org.flow.core.server.ServiceInstance;
import org.flow.core.server.ServiceLivenessManager;
import org.flow.core.server.ServiceLivenessManagerTest;
import org.flow.core.server.ServiceLivenessUpdater;
import org.flow.core.server.ServiceRegistry;
import org.flow.core.server.ServiceStateTransition;
import org.flow.core.server.WorkerTaskRestartStrategy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import org.flow.core.contexts.FlowContext;
import org.flow.core.models.ServerType;
import org.flow.core.server.*;

import static org.flow.core.server.ServiceLivenessManagerTest.newServiceForState;
import static org.flow.core.server.ServiceLivenessManagerTest.serviceInstanceFor;
import static org.flow.core.server.ServiceStateTransition.Result.SUCCEEDED;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith({ MockitoExtension.class })
@MockitoSettings(strictness = Strictness.LENIENT)
class JdbcServiceLivenessManagerTest {

    private static final Duration DEFAULT_DURATION = Duration.ofSeconds(5);

    @Mock
    public ServiceLivenessUpdater serviceLivenessUpdater;

    private ServiceLivenessManager serviceLivenessManager;

    @Mock
    private FlowContext context;

    @BeforeEach
    void beforeEach() {
        Mockito.when(context.getServerType()).thenReturn(ServerType.WORKER);
        Mockito.when(context.getVersion()).thenReturn("");
        FlowContext.setContext(context);
        ServerConfig config = new ServerConfig(
            Duration.ZERO,
            WorkerTaskRestartStrategy.AFTER_TERMINATION_GRACE_PERIOD,
            new ServerConfig.Liveness(
                true,
                Duration.ZERO,
                DEFAULT_DURATION, // timeout
                DEFAULT_DURATION,
                DEFAULT_DURATION
            ),
            null,
            null,
            null
        );

        this.serviceLivenessManager = new ServiceLivenessManager(
            config,
            new ServiceRegistry(),
            new LocalServiceStateFactory(config, null),
            new ServerInstanceFactory(context, null),
            serviceLivenessUpdater
        );
    }

    @Test
    void shouldRunOnStateTransitionFailureWhenTimeoutForWorker() {
        // Given
        Service running = newServiceForState(Service.ServiceState.RUNNING);
        serviceLivenessManager.updateServiceInstance(running, serviceInstanceFor(running));

        // When
        Instant now = Instant.now();
        final ServiceStateTransition.Response response = new ServiceStateTransition.Response(
            SUCCEEDED,
            serviceInstanceFor(running)
        );

        Mockito.when(serviceLivenessUpdater.update(any(ServiceInstance.class), any(Service.ServiceState.class))).thenReturn(response);
        serviceLivenessManager.run(now); // SUCCEED

        // Simulate exception on each transition
        Mockito.when(serviceLivenessUpdater.update(any(ServiceInstance.class), any(Service.ServiceState.class))).thenThrow(new RuntimeException());

        serviceLivenessManager.run(now.plus(Duration.ofSeconds(2))); // FAIL
        Mockito.verify(context, Mockito.never()).shutdown();

        serviceLivenessManager.run(now.plus(Duration.ofSeconds(4))); // FAIL
        Mockito.verify(context, Mockito.never()).shutdown();

        // Then
        serviceLivenessManager.run(now.plus(Duration.ofSeconds(6))); // TIMEOUT
        Mockito.verify(context, Mockito.times(1)).shutdown();
    }
}