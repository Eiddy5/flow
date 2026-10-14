package org.flow.core.secret;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.exceptions.InternalException;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.junit.annotations.LoadFlows;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.executions.LogEntry;
import org.flow.core.models.flows.State;
import org.flow.core.queues.DispatchQueueInterface;
import org.flow.core.queues.QueueException;
import org.flow.core.runners.TestRunnerUtils;
import org.flow.core.runners.VariableRenderer;
import org.flow.core.services.TaskOutputService;
import org.flow.core.utils.TestsUtils;

import io.micronaut.test.annotation.MockBean;
import jakarta.inject.Inject;

import static org.flow.core.tenant.TenantService.MAIN_TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@FlowTest(startRunner = true)
public class SecretFunctionTest {

    @Inject
    DispatchQueueInterface<LogEntry> logQueue;

    @Inject
    private TestRunnerUtils runnerUtils;

    @Inject
    private SecretService secretService;

    @Inject
    VariableRenderer variableRenderer;

    @Inject
    TaskOutputService taskOutputService;

    @Test
    @LoadFlows({ "flows/valids/secrets.yaml" })
    @EnabledIfEnvironmentVariable(named = "SECRET_MY_SECRET", matches = ".*")
    @EnabledIfEnvironmentVariable(named = "SECRET_NEW_LINE", matches = ".*")
    void getSecret() throws TimeoutException, QueueException, InternalException {
        List<LogEntry> logs = new CopyOnWriteArrayList<>();
        logQueue.addListener(logs::add);

        Execution execution = runnerUtils.runOne(MAIN_TENANT, "org.flow.tests", "secrets");
        assertThat(taskOutputService.getOutputs(execution.getTaskRunList().getFirst()).get("value")).isEqualTo("secretValue");
        assertThat(taskOutputService.getOutputs(execution.getTaskRunList().get(2)).get("value"))
            .isEqualTo("passwordveryveryveyrlongpasswordveryveryveyrlongpasswordveryveryveyrlongpasswordveryveryveyrlongpasswordveryveryveyrlong");
        assertThat(taskOutputService.getOutputs(execution.getTaskRunList().get(3)).get("value")).isEqualTo("secretValue");
        assertThat(taskOutputService.getOutputs(execution.getTaskRunList().get(4))).isEmpty();
        assertThat(execution.getTaskRunList().get(4).getState().getCurrent()).isEqualTo(State.Type.WARNING);

        LogEntry matchingLog = TestsUtils.awaitLog(logs, logEntry -> logEntry.getTaskId() != null && logEntry.getTaskId().equals("log-secret"));
        assertThat(matchingLog.getMessage()).contains("***");
    }

    @Test
    void shouldGetSecretGivenExistingSubKey() throws IllegalVariableEvaluationException {
        // Given
        Map<String, Object> context = Map.of(
            "flow", Map.of("namespace", "org.flow.unittest")
        );

        // When / Then
        assertThat(variableRenderer.render("{{ secret('json-secret', subkey='string') }}", context)).isEqualTo("value");
        assertThat(variableRenderer.render("{{ secret('json-secret', subkey='number') }}", context)).isEqualTo("42");
        assertThat(variableRenderer.render("{{ secret('json-secret', subkey='array') }}", context)).isEqualTo("[\"one\",\"two\",\"three\"]");
        assertThat(variableRenderer.render("{{ secret('json-secret', subkey='object') }}", context)).isEqualTo("{\"f1\":\"value1\",\"f2\":\"value2\"}");
        assertThat(variableRenderer.render("{{ secret('json-secret', subkey='boolean') }}", context)).isEqualTo("true");
    }

    @Test
    void shouldFailedGivenNonExistingSubKey() {
        // Given
        Map<String, Object> context = Map.of(
            "flow", Map.of("namespace", "org.flow.unittest")
        );

        // When / Then
        Throwable cause = Assertions.assertThrows(IllegalVariableEvaluationException.class, () ->
        {
            variableRenderer.render("{{ secret('json-secret', subkey='missing') }}", context);
        }).getCause();
        assertThat(cause.getMessage()).isEqualTo("Cannot find secret sub-key 'missing' in secret 'json-secret'. ({{ secret('json-secret', subkey='missing') }}:1)");
    }

    @Test
    void shouldFailedGivenExistingButInvalidSubKey() {
        // Given
        Map<String, Object> context = Map.of(
            "flow", Map.of("namespace", "org.flow.unittest")
        );

        // When / Then
        Throwable cause = Assertions.assertThrows(IllegalVariableEvaluationException.class, () ->
        {
            variableRenderer.render("{{ secret('string-secret', subkey='???') }}", context);
        }).getCause();
        assertThat(cause.getMessage())
            .isEqualTo("Failed to read secret sub-key '???' from secret 'string-secret'. Ensure the secret contains valid JSON value. ({{ secret('string-secret', subkey='???') }}:1)");
    }

    @Test
    void shouldGetSecretGivenFull() throws IllegalVariableEvaluationException {
        // Given
        Map<String, Object> context = Map.of(
            "flow", Map.of("namespace", "org.flow.unittest")
        );

        // When / Then
        // structured secret manager: value plus metadata
        assertThat(variableRenderer.render("{{ secret('credential', full=true).value }}", context)).isEqualTo("my-password");
        assertThat(variableRenderer.render("{{ secret('credential', full=true).metadata.username }}", context)).isEqualTo("my-user");
        assertThat(variableRenderer.render("{{ secret('credential', full=true).metadata.domain }}", context)).isEqualTo("flow-io");

        // single-value secret: only value, no metadata
        assertThat(variableRenderer.render("{{ secret('string-secret', full=true).value }}", context)).isEqualTo("string-value");
    }

    @Test
    void shouldFailedGivenBothSubKeyAndFull() {
        // Given
        Map<String, Object> context = Map.of(
            "flow", Map.of("namespace", "org.flow.unittest")
        );

        // When / Then
        Throwable cause = Assertions.assertThrows(IllegalVariableEvaluationException.class, () ->
        {
            variableRenderer.render("{{ secret('json-secret', subkey='string', full=true) }}", context);
        }).getCause();
        assertThat(cause.getMessage())
            .isEqualTo("The 'secret' function cannot be called with both 'subkey' and 'full' arguments. ({{ secret('json-secret', subkey='string', full=true) }}:1)");
    }

    @Test
    void getUnknownSecret() {
        var exception = assertThrows(SecretNotFoundException.class, () -> secretService.findSecret(null, null, "unknown_secret_key"));
        assertThat(exception.getMessage()).isEqualTo("Cannot find secret for key 'unknown_secret_key'.");
    }

    @MockBean(SecretService.class)
    public static class TestSecretService extends SecretService {

        private static final Map<String, String> SECRETS = Map.of(
            "org.flow.unittest.json-secret", """
                {
                "string": "value",
                "number": 42,
                "boolean": true,
                "array": ["one", "two", "three"],
                "object": {"f1": "value1", "f2": "value2"}
                }
                """,
            "org.flow.unittest.string-secret", "string-value"
        );

        public String findSecret(String tenantId, String namespace, String key) throws SecretNotFoundException, IOException {
            Optional<String> optional = Optional.ofNullable(SECRETS.get(namespace + "." + key));
            if (optional.isPresent()) {
                return optional.get();
            }
            return super.findSecret(tenantId, namespace, key);
        }

        @Override
        public SecretObject findSecretObject(String tenantId, String namespace, String key) throws SecretNotFoundException, IOException {
            if ("credential".equals(key)) {
                Map<String, String> metadata = new LinkedHashMap<>();
                metadata.put("username", "my-user");
                metadata.put("domain", "flow-io");
                return new SecretObject("my-password", metadata);
            }
            return super.findSecretObject(tenantId, namespace, key);
        }
    }
}
