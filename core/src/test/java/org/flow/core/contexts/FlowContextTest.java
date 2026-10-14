package org.flow.core.contexts;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import org.flow.core.utils.EditionProvider;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@MicronautTest
class FlowContextTest {

    @Inject
    FlowContext context;

    @Test
    void shouldGetEdition() {
        assertThat(context.getEdition()).isEqualTo(EditionProvider.Edition.OSS);
    }

    @Test
    void shouldGetWorkerMaxNumThreads() throws InterruptedException {
        // When
        context.injectWorkerConfigs(16);

        // Then
        assertThat(context.getWorkerMaxNumThreads()).isEqualTo(Optional.of(16));
    }

    @Test
    void shouldGetAllocatedCpuCores() {
        assertThat(context.getAllocatedCpuCores()).isEqualTo(Runtime.getRuntime().availableProcessors());
    }
}
