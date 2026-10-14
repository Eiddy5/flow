package org.flow.core.server;

import java.time.Duration;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.FlowTest;

import jakarta.inject.Inject;

@FlowTest
class ServerConfigTest {

    @Inject
    ServerConfig config;

    @Test
    void test() {
        Assertions.assertNotNull(config);
        Assertions.assertEquals(config.liveness().interval(), Duration.ofSeconds(5));
        Assertions.assertNotNull(config.liveness().initialDelay());
        Assertions.assertNotNull(config.liveness().timeout());
        Assertions.assertNotNull(config.liveness().heartbeatInterval());
    }

}