package org.flow.core.plugins;

import org.flow.core.models.Plugin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import static org.flow.core.utils.RegexPatterns.JAVA_IDENTIFIER_REGEX;

@org.flow.core.models.annotations.Plugin
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
public abstract class AdditionalPlugin implements Plugin {
    @NotNull
    @NotBlank
    @Pattern(regexp = JAVA_IDENTIFIER_REGEX)
    protected String type;
}
