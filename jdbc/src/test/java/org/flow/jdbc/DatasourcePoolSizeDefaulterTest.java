package org.flow.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.micronaut.configuration.jdbc.hikari.DatasourceConfiguration;
import io.micronaut.context.env.Environment;
import io.micronaut.context.event.BeanCreatedEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatasourcePoolSizeDefaulterTest {

    /** 其它业务数据源保留自己的默认值，不受引擎池配置影响。 */
    @Test
    void shouldLeaveOtherApplicationPoolsUnchanged() {
        DatasourceConfiguration configuration = new DatasourceConfiguration("default");
        when(event.getBean()).thenReturn(configuration);
        int originalMaximum = configuration.getMaximumPoolSize();
        int originalMinimum = configuration.getMinimumIdle();

        new DatasourcePoolSizeDefaulter(environment).onCreated(event);

        assertThat(configuration.getMaximumPoolSize()).isEqualTo(originalMaximum);
        assertThat(configuration.getMinimumIdle()).isEqualTo(originalMinimum);
        org.mockito.Mockito.verifyNoInteractions(environment);
    }

    @Mock
    private Environment environment;

    @Mock
    private BeanCreatedEvent<DatasourceConfiguration> event;

    @Test
    void shouldRaiseDefaultPoolSizeWhenNotExplicitlyConfigured() {
        // Given
        DatasourceConfiguration configuration = new DatasourceConfiguration("flow");
        when(event.getBean()).thenReturn(configuration);
        when(environment.containsProperty("datasources.flow.maximum-pool-size")).thenReturn(false);

        // When
        DatasourceConfiguration result = new DatasourcePoolSizeDefaulter(environment).onCreated(event);

        // Then
        assertThat(result.getMaximumPoolSize()).isEqualTo(20);
    }

    @Test
    void shouldNotOverrideExplicitlyConfiguredPoolSize() {
        // Given
        DatasourceConfiguration configuration = new DatasourceConfiguration("flow");
        configuration.setMaximumPoolSize(10);
        when(event.getBean()).thenReturn(configuration);
        when(environment.containsProperty("datasources.flow.maximum-pool-size")).thenReturn(true);

        // When
        DatasourceConfiguration result = new DatasourcePoolSizeDefaulter(environment).onCreated(event);

        // Then
        assertThat(result.getMaximumPoolSize()).isEqualTo(10);
    }

    @Test
    void shouldDeriveThePropertyPathFromTheDatasourceName() {
        // Given
        DatasourceConfiguration configuration = new DatasourceConfiguration("flow");
        when(event.getBean()).thenReturn(configuration);

        // When
        new DatasourcePoolSizeDefaulter(environment).onCreated(event);

        // Then
        verify(environment).containsProperty("datasources.flow.maximum-pool-size");
    }
}
