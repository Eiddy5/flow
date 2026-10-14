package org.flow.plugin.core.storage;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.URI;

import org.junit.jupiter.api.Test;

import com.google.common.io.CharStreams;

import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.models.property.Property;
import org.flow.core.runners.RunContext;
import org.flow.core.runners.RunContextFactory;
import org.flow.core.storages.StorageInterface;

import jakarta.inject.Inject;

import static org.flow.core.tenant.TenantService.MAIN_TENANT;
import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
class ReverseTest {
    @Inject
    RunContextFactory runContextFactory;

    @Inject
    StorageInterface storageInterface;

    @Test
    void run() throws Exception {
        RunContext runContext = runContextFactory.of();

        URI put = storageInterface.put(
            MAIN_TENANT,
            null,
            new URI("/file/storage/get.yml"),
            new ByteArrayInputStream("1\n2\n3\n".getBytes())
        );

        Reverse result = Reverse.builder()
            .from(Property.ofValue(put.toString()))
            .build();

        Reverse.Output run = result.run(runContext);

        assertThat(run.getUri().getPath()).endsWith(".yml");
        assertThat(CharStreams.toString(new InputStreamReader(storageInterface.get(MAIN_TENANT, null, run.getUri())))).isEqualTo("3\n2\n1\n");
    }
}