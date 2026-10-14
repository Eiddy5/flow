package org.flow.queue;

import io.micronaut.context.annotation.ConfigurationProperties;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Getter
@ConfigurationProperties(value = "flow.queue")
public class QueueConfiguration {

    @NotNull
    String type;

    @Nullable
    MessageProtection messageProtection;

    @Nullable
    String prefix;

    Boolean failFast = true;

    @Getter
    @ConfigurationProperties("message-protection")
    public static class MessageProtection {
        Boolean enabled = false;
        Integer limit;
    }
}
