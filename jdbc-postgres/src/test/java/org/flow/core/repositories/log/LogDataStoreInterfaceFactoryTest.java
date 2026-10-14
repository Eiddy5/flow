package org.flow.core.repositories.log;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.flow.core.exceptions.FlowRuntimeException;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link LogDataStoreInterfaceFactory}: plugin discovery and selection by
 * {@code flow.logs.type}.
 * <p>
 * Lives in the {@code jdbc-postgres} module so at least one log store ({@code PostgresLogDataStore}) is on the
 * classpath and discoverable via the plugin registry. It exercises the factory, not the store —
 * the store's behavior is covered by {@code PostgresLogDataStoreTest} (the shared log data store suite).
 */
@MicronautTest
class LogDataStoreInterfaceFactoryTest {

    @Inject
    LogDataStoreInterfaceFactory logRepositoryInterfaceFactory;

    @Test
    void shouldListDiscoveredLogDataStoreTypes() {
        assertThat(logRepositoryInterfaceFactory.getLoggableTypeIds()).contains("postgres");
    }

    @Test
    void shouldFailWithClearMessageForUnknownType() {
        assertThatThrownBy(() -> logRepositoryInterfaceFactory.make("unknown", Map.of()))
            .isInstanceOf(FlowRuntimeException.class)
            .hasMessageContaining("flow.logs.type=unknown")
            .hasMessageContaining("Supported types are")
            .hasMessageContaining("postgres");
    }
}
