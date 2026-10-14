package org.flow.core.validations;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import io.micronaut.context.exceptions.BeanInstantiationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppConfigValidatorTest {

    @Test
    void validateNoFlowUrl() {
        assertThatCode(() ->
        {
            try (ApplicationContext context = ApplicationContext.run()) {
                context.getBean(AppConfigValidator.class);
            }
        })
            .as("The bean got initialized properly including the PostConstruct validation")
            .doesNotThrowAnyException();
    }

    @Test
    void validateValidFlowUrl() {
        assertThatCode(() ->
        {
            try (
                ApplicationContext context = ApplicationContext.builder()
                    .deduceEnvironment(false)
                    .environments("test")
                    .properties(
                        Map.of("flow.url", "https://flow.example.com")
                    )
                    .start()
            ) {
                context.getBean(AppConfigValidator.class);
            }
        })
            .as("The bean got initialized properly including the PostConstruct validation")
            .doesNotThrowAnyException();
    }

    @Test
    void validateInvalidFlowUrl() {
        assertThatThrownBy(() ->
        {
            try (
                ApplicationContext context = ApplicationContext.builder()
                    .deduceEnvironment(false)
                    .environments("test")
                    .properties(
                        Map.of("flow.url", "postgres-oss.preview.dev.flow.io")
                    )
                    .start()
            ) {
                context.getBean(AppConfigValidator.class);
            }
        })
            .as("The bean initialization failed at PostConstruct")
            .isInstanceOf(BeanInstantiationException.class)
            .hasMessageContaining("Invalid configuration");
    }

    @Test
    void validateNonHttpFlowUrl() {
        assertThatThrownBy(() ->
        {
            try (
                ApplicationContext context = ApplicationContext.builder()
                    .deduceEnvironment(false)
                    .environments("test")
                    .properties(
                        Map.of("flow.url", "ftp://postgres-oss.preview.dev.flow.io")
                    )
                    .start()
            ) {
                context.getBean(AppConfigValidator.class);
            }
        })
            .as("The bean initialization failed at PostConstruct")
            .isInstanceOf(BeanInstantiationException.class)
            .hasMessageContaining("Invalid configuration");
    }

    @Test
    void shouldReturnValidResultWhenFlowUrlAbsent() {
        Environment environment = mock(Environment.class);
        when(environment.containsProperty("flow.url")).thenReturn(false);

        List<ConfigValidationResult> results = AppConfigValidator.validateConfiguration(environment);

        assertThat(results)
            .as("An absent flow.url is valid")
            .allMatch(ConfigValidationResult::valid);
    }

    @Test
    void shouldReturnValidResultWhenFlowUrlValid() {
        Environment environment = mock(Environment.class);
        when(environment.containsProperty("flow.url")).thenReturn(true);
        when(environment.getProperty("flow.url", String.class)).thenReturn(Optional.of("https://your.company.com"));

        List<ConfigValidationResult> results = AppConfigValidator.validateConfiguration(environment);

        assertThat(results)
            .as("A well-formed HTTPS flow.url is valid")
            .allMatch(ConfigValidationResult::valid);
    }

    @Test
    void shouldReturnInvalidResultWhenFlowUrlMalformed() {
        Environment environment = mock(Environment.class);
        when(environment.containsProperty("flow.url")).thenReturn(true);
        when(environment.getProperty("flow.url", String.class)).thenReturn(Optional.of("not a url"));

        List<ConfigValidationResult> results = AppConfigValidator.validateConfiguration(environment);

        assertThat(results)
            .as("A malformed flow.url is reported as invalid with a message")
            .anyMatch(result -> !result.valid() && result.key().equals("flow.url") && result.message() != null);
    }

    @Test
    void shouldReturnInvalidResultWhenFlowUrlNotHttp() {
        Environment environment = mock(Environment.class);
        when(environment.containsProperty("flow.url")).thenReturn(true);
        when(environment.getProperty("flow.url", String.class)).thenReturn(Optional.of("ftp://your.company.com"));

        List<ConfigValidationResult> results = AppConfigValidator.validateConfiguration(environment);

        assertThat(results)
            .as("A non-HTTP flow.url scheme is reported as invalid")
            .anyMatch(result -> !result.valid() && result.key().equals("flow.url"));
    }
}