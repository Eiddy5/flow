package org.flow.plugin.core.storage;

import java.io.InputStream;

import org.junit.jupiter.api.Test;

import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.property.Property;
import org.flow.core.runners.RunContext;
import org.flow.core.runners.RunContextFactory;
import org.flow.core.storages.StorageInterface;

import jakarta.inject.Inject;

import static org.flow.core.tenant.TenantService.MAIN_TENANT;
import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
class WriteTest {
    @Inject
    RunContextFactory runContextFactory;

    @Inject
    StorageInterface storageInterface;

    @Test
    void run() throws Exception {
        RunContext runContext = runContextFactory.of();

        Write write = Write.builder()
            .content(Property.ofValue("Hello World"))
            .extension(Property.ofValue(".txt"))
            .build();

        var output = write.run(runContext);
        assertThat(output).isNotNull();
        assertThat(output.getUri()).isNotNull();

        InputStream inputStream = storageInterface.get(MAIN_TENANT, null, output.getUri());
        assertThat(inputStream).isNotNull();
        inputStream.close();
    }
}