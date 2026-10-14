package org.flow.core.http.client.configurations;

import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.runners.RunContext;

import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = true, include = JsonTypeInfo.As.EXISTING_PROPERTY)
@JsonSubTypes(
    {
        @JsonSubTypes.Type(value = BasicAuthConfiguration.class, name = "BASIC"),
        @JsonSubTypes.Type(value = BearerAuthConfiguration.class, name = "BEARER"),
        @JsonSubTypes.Type(value = DigestAuthConfiguration.class, name = "DIGEST")
    }
)
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
public abstract class AbstractAuthConfiguration {
    public abstract AuthType getType();

    public abstract void configure(HttpClientBuilder builder, RunContext runContext) throws IllegalVariableEvaluationException;

    public enum AuthType {
        BASIC,
        BEARER,
        DIGEST
    }
}
