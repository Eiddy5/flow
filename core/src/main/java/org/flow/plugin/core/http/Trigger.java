package org.flow.plugin.core.http;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;

import org.flow.core.http.client.configurations.HttpConfiguration;
import org.flow.plugin.api.annotations.Example;
import org.flow.plugin.api.annotations.Plugin;
import org.flow.core.models.conditions.ConditionContext;
import org.flow.core.models.property.Property;
import org.flow.core.models.triggers.*;
import org.flow.core.runners.RunContext;
import org.flow.core.utils.TruthUtils;

import io.micronaut.http.MediaType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Poll an HTTP endpoint and trigger when a condition matches.",
    description = """
        Periodically calls `uri` (default GET every 60s), evaluates `responseCondition` against status/body/headers, and launches the flow when true. Supports request customization (method, params, headers, auth via options) and `stopAfter` states to disable once satisfied."""
)
@Plugin(
    examples = {
        @Example(
            title = "Send a Slack alert if the price is below a certain threshold. The flow will be triggered every 30 seconds until the condition is met. Then, the `stopAfter` property will disable the trigger to avoid unnecessary API calls and alerts.",
            full = true,
            code = """
                id: http_price_alert
                namespace: company.team

                tasks:
                  - id: send_slack_alert
                    type: org.flow.plugin.slack.SlackIncomingWebhook
                    url: "{{ secret('SLACK_WEBHOOK') }}"
                    payload: |
                      {
                        "channel": "#price-alerts",
                        "text": "The price is now: {{ json(trigger.body).price }}"
                      }

                triggers:
                  - id: http
                    type: org.flow.plugin.core.http.Trigger
                    uri: https://fakestoreapi.com/products/1
                    responseCondition: "{{ json(response.body).price <= 110 }}"
                    interval: PT30S
                    stopAfter:
                      - SUCCESS
                """
        ),
        @Example(
            title = "Trigger a flow if an HTTP endpoint returns a status code equals to 200",
            full = true,
            code = """
                id: http_trigger
                namespace: company.team

                tasks:
                  - id: log_response
                    type: org.flow.plugin.core.log.Log
                    message: '{{ trigger.body }}'

                triggers:
                  - id: http
                    type: org.flow.plugin.core.http.Trigger
                    uri: https://api.chucknorris.io/jokes/random
                    responseCondition: "{{ response.statusCode == 200 }}"
                    stopAfter:
                      - SUCCESS
                """
        )
    },
    aliases = "org.flow.plugin.fs.http.Trigger"
)
public class Trigger extends AbstractTrigger implements PollingTriggerInterface, HttpInterface, TriggerOutput<Request.Output> {
    @Builder.Default
    private final Duration interval = Duration.ofSeconds(60);

    @Schema(
        title = "The condition on the HTTP response to trigger a flow, which can be any expression that evaluates to a boolean value.",
        description = """
            The condition will be evaluated after calling the HTTP endpoint; it can use the response itself to determine whether to start a flow or not.
            The following variables are available when evaluating the condition:
            - `response.statusCode`: the response HTTP status code
            - `response.body`: the response body as a string
            - `response.headers`: the response headers

            Boolean coercion allows 0, -0, null and '' to evaluate to false, all other values will evaluate to true.

            The condition will be evaluated before any 'generic trigger conditions' that can be configured via the `conditions` property.
            """
    )
    @Builder.Default
    @NotNull
    private Property<String> responseCondition = Property.ofExpression("{{ response.statusCode < 400 }}");

    @NotNull
    private Property<String> uri;

    @Builder.Default
    private Property<String> method = Property.ofValue("GET");

    private Property<String> body;

    private Property<Map<String, Object>> params;

    private Property<Map<String, Object>> formData;

    @Builder.Default
    private Property<String> contentType = Property.ofValue(MediaType.APPLICATION_JSON);

    private Property<Map<CharSequence, CharSequence>> headers;

    private HttpConfiguration options;

    @Builder.Default
    @Schema(
        title = "If true, the HTTP response body will be automatically encrypted and decrypted in the outputs if encryption is configured.",
        description = "When true, the `encryptedBody` output will be filled, otherwise the `body` output will be filled."
    )
    private Property<Boolean> encryptBody = Property.ofValue(false);

    @Override
    public Optional<TriggerEvaluationResult> eval(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();
        Logger logger = runContext.logger();

        if (this.options == null) {
            this.options = HttpConfiguration.builder().build();
        }
        // we allow failed status code as it is the condition that must determine whether we trigger the flow
        options.setAllowFailed(Property.ofValue(true));
        options.setSsl(this.options.getSsl());

        var request = Request.builder()
            .uri(this.uri)
            .method(this.method)
            .body(this.body)
            .formData(this.formData)
            .contentType(this.contentType)
            .headers(this.headers)
            .options(this.options)
            .encryptBody(this.encryptBody)
            .build();
        var output = request.run(runContext);

        logger.debug("{} respond with status code '{}'", output.getUri(), output.getCode());

        Object body = runContext.render(this.encryptBody).as(Boolean.class).orElseThrow()
            ? runContext.decrypt(output.getEncryptedBody().getValue())
            : output.getBody();

        Map<String, Object> response = new HashMap<>();
        response.put("statusCode", output.getCode());
        response.put("body", body); // body can be null so we need a null-friendly map
        response.put("headers", output.getHeaders());
        Map<String, Object> responseVariables = Map.of("response", response);
        String renderedCondition = runContext.render(this.responseCondition).as(String.class, responseVariables).orElse(null);
        if (TruthUtils.isTruthy(renderedCondition)) {
            return Optional.of(TriggerService.generateEvaluationResult(this, conditionContext, output));
        }

        return Optional.empty();
    }
}
