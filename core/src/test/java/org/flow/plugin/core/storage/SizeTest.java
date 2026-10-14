package org.flow.plugin.core.storage;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Random;

import org.junit.jupiter.api.Test;

import org.flow.core.context.TestRunContextFactory;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.property.Property;
import org.flow.core.runners.RunContext;
import org.flow.core.storages.StorageInterface;

import jakarta.inject.Inject;

import static org.flow.core.tenant.TenantService.MAIN_TENANT;
import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
class SizeTest {
    @Inject
    TestRunContextFactory runContextFactory;

    @Inject
    StorageInterface storageInterface;

    @Test
    void run() throws Exception {
        RunContext runContext = runContextFactory.of();

        final Long size = 42L;
        byte[] randomBytes = new byte[size.intValue()];
        new Random().nextBytes(randomBytes);

        URI put = storageInterface.put(
            MAIN_TENANT,
            null,
            new URI("/file/storage/get.yml"),
            new ByteArrayInputStream(randomBytes)
        );

        Size bash = Size.builder()
            .uri(Property.ofValue(put.toString()))
            .build();

        Size.Output run = bash.run(runContext);
        assertThat(run.getSize()).isEqualTo(size);
    }
}
