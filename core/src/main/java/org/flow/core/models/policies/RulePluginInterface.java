package org.flow.core.models.policies;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

import org.flow.core.models.annotations.Plugin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import static org.flow.core.utils.RegexPatterns.JAVA_IDENTIFIER_REGEX;

/**
 * Top-level marker interface for Flow's plugin of type Rule (Enterprise Edition governance policies).
 */
@Plugin
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "type", visible = true, include = JsonTypeInfo.As.EXISTING_PROPERTY)
public interface RulePluginInterface extends org.flow.core.models.Plugin {
    @Schema(
        title = "The type of the rule."
    )
    @NotNull
    @NotBlank
    @Pattern(regexp = JAVA_IDENTIFIER_REGEX)
    String getType();
}
