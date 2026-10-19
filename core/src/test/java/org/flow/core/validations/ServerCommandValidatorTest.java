package org.flow.core.validations;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.flow.core.models.ServerType;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import io.micronaut.context.exceptions.BeanInstantiationException;
import io.micronaut.context.exceptions.NoSuchBeanException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServerCommandValidatorTest {

    @Test
    void noServerCommandIssued() {
        // deduceEnvironment(false) prevents picking up the "test" environment
        // (and its application-test.yml which sets flow.server-type), so we
        // can verify the validator is genuinely absent when no server command
        // is issued.
        try (
            ApplicationContext context = ApplicationContext.builder()
                .deduceEnvironment(false)
                .start()
        ) {
            Assertions.assertThrows(NoSuchBeanException.class, () -> context.getBean(ServerCommandValidator.class));
        }
    }

    /** 验证完整服务配置可启动，缺少必需配置时报告配置错误。 */
    @Test
    void serverCommandIssued() {
        try (ApplicationContext context = ApplicationContext.builder()
            .deduceEnvironment(false)
            .environments("test")
            .properties(Map.of("flow.server-type", "webserver", "flow.queue.type", "postgres",
                "flow.repository.type", "postgres", "flow.storage.type", "external"))
            .start()) {
            assertThat(context.getBean(ServerCommandValidator.class)).isNotNull();
        }

        final Throwable exception = Assertions.assertThrows(
            BeanInstantiationException.class, () -> ApplicationContext.builder()
                .deduceEnvironment(false)
                .properties(
                    Map.of(
                        "flow.server-type", "webserver",
                        "flow.repository.type", "postgres",
                        "flow.queue.type", "postgres",
                        "datasources.flow.url", System.getenv("FLOW_UPSTREAM_TEST_URL"),
                        "datasources.flow.username", System.getenv("FLOW_UPSTREAM_TEST_USER"),
                        "datasources.flow.password", System.getenv("FLOW_UPSTREAM_TEST_PASSWORD"),
                        "datasources.flow.driverClassName", "org.postgresql.Driver"
                    )
                )
                .start()
        );
        final Throwable rootException = getRootException(exception);
        assertThat(rootException.getClass()).isEqualTo(ServerCommandValidator.ServerCommandException.class);
        assertThat(rootException.getMessage()).isEqualTo("Incomplete server configuration - missing required properties");
    }

    private Throwable getRootException(Throwable exception) {
        while (exception.getCause() != null) {
            exception = exception.getCause();
        }
        return exception;
    }

    @Test
    void shouldReturnValidResultsWhenAllRequiredPropertiesPresent() {
        Environment environment = mock(Environment.class);
        when(environment.containsProperty("flow.queue.type")).thenReturn(true);
        when(environment.containsProperty("flow.repository.type")).thenReturn(true);
        when(environment.containsProperty("flow.storage.type")).thenReturn(true);

        List<ConfigValidationResult> results = ServerCommandValidator.validateServerConfiguration(environment, ServerType.WEBSERVER);

        assertThat(results)
            .as("All required webserver properties present is valid")
            .isNotEmpty()
            .allMatch(ConfigValidationResult::valid);
    }

    @Test
    void shouldReturnInvalidResultWhenRequiredPropertyMissing() {
        Environment environment = mock(Environment.class);
        when(environment.containsProperty("flow.queue.type")).thenReturn(true);
        when(environment.containsProperty("flow.repository.type")).thenReturn(true);
        when(environment.containsProperty("flow.storage.type")).thenReturn(false);

        List<ConfigValidationResult> results = ServerCommandValidator.validateServerConfiguration(environment, ServerType.WEBSERVER);

        assertThat(results)
            .as("A missing flow.storage.type is reported as invalid with a message")
            .anyMatch(result -> !result.valid() && result.key().equals("flow.storage.type") && result.message() != null);
    }

    @Test
    void shouldRequireQueueAndRepositoryForWorker() {
        Environment environment = mock(Environment.class);
        when(environment.containsProperty("flow.storage.type")).thenReturn(true);

        List<ConfigValidationResult> results = ServerCommandValidator.validateServerConfiguration(environment, ServerType.WORKER);

        assertThat(results)
            .as("A JDBC worker requires queue and repository configuration")
            .hasSize(3)
            .filteredOn(result -> !result.valid())
            .extracting(ConfigValidationResult::key)
            .containsExactlyInAnyOrder("flow.queue.type", "flow.repository.type");
    }
}