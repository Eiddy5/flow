package org.flow.core.contexts;

import java.util.Map;

import org.flow.core.repositories.log.LogsConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowBeansFactoryTest {

    @Test
    void shouldRejectExternalLogStoreWhenLogTypeConfigured() {
        FlowBeansFactory factory = new FlowBeansFactory();
        factory.logsConfig = new LogsConfig(Map.of("type", "elasticsearch"));

        assertThatThrownBy(factory::ensureLogDataStoreAllowed)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("flow.logs.type=elasticsearch")
            .hasMessageContaining("Enterprise Edition");
    }

    @Test
    void shouldAllowLogDataStoreWhenNoLogTypeConfigured() {
        FlowBeansFactory factory = new FlowBeansFactory();
        factory.logsConfig = new LogsConfig(null);

        assertThatCode(factory::ensureLogDataStoreAllowed).doesNotThrowAnyException();
    }
}
