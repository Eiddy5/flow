package org.flow.core.validations;

import java.io.Serial;
import java.util.List;

import org.flow.core.models.ServerType;
import org.flow.core.utils.Enums;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Context
@Requires(property = "flow.server-type")
public class ServerCommandValidator {
    private static final List<String> ALL_REQUIRED_PROPERTIES = List.of(
        "flow.queue.type", "flow.repository.type", "flow.storage.type"
    );

    private final Environment environment;

    private final ServerType serverType;

    @Inject
    public ServerCommandValidator(final Environment environment) {
        this.environment = environment;
        this.serverType = Enums.getForNameIgnoreCase(environment.getRequiredProperty("flow.server-type", String.class), ServerType.class);
    }

    @PostConstruct
    void validate() {
        final List<ConfigValidationResult> results = validateServerConfiguration(environment, serverType);

        results.stream()
            .filter(result -> !result.valid())
            .forEach(result -> log.error(result.message()));

        if (results.stream().anyMatch(result -> !result.valid())) {
            throw new ServerCommandException("Incomplete server configuration - missing required properties");
        }


    }

    /**
     * Validates that the properties required to start the given {@link ServerType} are defined.
     *
     * <p>
     * This method is side-effect free (it neither logs nor throws) so the same checks can be
     * reused for on-demand validation.
     *
     * @param environment the configuration environment to validate
     * @param serverType the server type whose required properties must be present
     * @return the outcome of each required-property check, never {@code null}
     */
    public static List<ConfigValidationResult> validateServerConfiguration(final Environment environment, final ServerType serverType) {
        return ALL_REQUIRED_PROPERTIES.stream()
            .map(
                property -> environment.containsProperty(property)
                    ? ConfigValidationResult.valid(property)
                    : ConfigValidationResult.invalid(property, missingPropertyMessage(property))
            )
            .toList();
    }

    private static String missingPropertyMessage(final String key) {
        return """
            Server configuration requires the '%s' property to be defined.
            See the configuration example in docs/harness/java-engine.md.""".formatted(key);
    }

    public static class ServerCommandException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        public ServerCommandException(String errorMessage) {
            super(errorMessage);
        }
    }
}
