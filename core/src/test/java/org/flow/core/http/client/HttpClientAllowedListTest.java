package org.flow.core.http.client;

import java.io.IOException;
import java.net.URI;

import org.junit.jupiter.api.Test;

import org.flow.core.context.TestRunContextFactory;
import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.http.HttpRequest;
import org.flow.core.junit.annotations.FlowTest;

import io.micronaut.context.annotation.Property;
import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@FlowTest
@Property(name = "flow.tasks.http.allowed-list", value = "http://localhost")
class HttpClientAllowedListTest {
    @Inject
    private TestRunContextFactory runContextFactory;

    private HttpClient client() throws IllegalVariableEvaluationException {
        return HttpClient.builder().runContext(runContextFactory.of()).build();
    }

    @Test
    void shouldRejectUriWhenHostIsDisguisedViaUserInfo() throws IllegalVariableEvaluationException, IOException {
        try (HttpClient client = client()) {
            var exception = assertThrows(IllegalArgumentException.class, () -> client.request(
                HttpRequest.of(URI.create("http://localhost@169.254.169.254/")),
                String.class
            ));
            assertThat(exception.getMessage()).isEqualTo("The URI http://localhost@169.254.169.254/ is not in the configured allowed list (flow.tasks.http.allowed-list).");
        }
    }

    @Test
    void shouldRejectUriWhenHostIsDisguisedViaSubdomainSuffix() throws IllegalVariableEvaluationException, IOException {
        try (HttpClient client = client()) {
            var exception = assertThrows(IllegalArgumentException.class, () -> client.request(
                HttpRequest.of(URI.create("http://localhost.attacker.example/")),
                String.class
            ));
            assertThat(exception.getMessage()).isEqualTo("The URI http://localhost.attacker.example/ is not in the configured allowed list (flow.tasks.http.allowed-list).");
        }
    }

    @Test
    void shouldAllowRequestWhenHostExactlyMatchesAllowedEntry() throws IllegalVariableEvaluationException, IOException {
        try (HttpClient client = client()) {
            // localhost matches the allowed list, so the request must fail on connecting to the
            // (almost certainly unbound) port rather than on the allow-list check itself.
            assertThrows(HttpClientRequestException.class, () -> client.request(
                HttpRequest.of(URI.create("http://localhost:1/")),
                String.class
            ));
        }
    }

    @Test
    void shouldRejectUriWhenHostIsASubdomainOfAnExactAllowedEntry() throws IllegalVariableEvaluationException, IOException {
        try (HttpClient client = client()) {
            var exception = assertThrows(IllegalArgumentException.class, () -> client.request(
                HttpRequest.of(URI.create("http://sub.localhost/")),
                String.class
            ));
            assertThat(exception.getMessage()).isEqualTo("The URI http://sub.localhost/ is not in the configured allowed list (flow.tasks.http.allowed-list).");
        }
    }

    @Test
    void shouldRejectUriWhenAuthorityHasNoParsableHost() throws IllegalVariableEvaluationException, IOException {
        try (HttpClient client = client()) {
            var exception = assertThrows(IllegalArgumentException.class, () -> client.request(
                HttpRequest.of(URI.create("http:///path")),
                String.class
            ));
            assertThat(exception.getMessage()).isEqualTo("The URI http:///path has no resolvable host to check against the configured allow/deny lists (flow.tasks.http.allowed-list / flow.tasks.http.denied-list).");
        }
    }
}
