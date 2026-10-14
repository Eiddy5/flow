package org.flow.core.validations;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.validations.ModelValidator;

import jakarta.inject.Inject;
import lombok.AllArgsConstructor;
import lombok.Getter;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
class JsonStringTest {
    @Inject
    private ModelValidator modelValidator;

    @AllArgsConstructor
    @Getter
    public static class JsonStringCls {
        @JsonString
        String json;
    }

    @Test
    void jsonString() throws Exception {
        JsonStringCls build = new JsonStringCls("{}");

        assertThat(modelValidator.isValid(build).isEmpty()).isTrue();

        build = new JsonStringCls("{\"invalid\"}");

        assertThat(modelValidator.isValid(build).isPresent()).isTrue();
        assertThat(modelValidator.isValid(build).get().getMessage()).contains("invalid json");
    }
}
