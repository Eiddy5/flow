package org.flow.core.models.triggers;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.flow.core.models.flows.Flow;
import org.flow.core.runners.RunContext;
import org.flow.core.storages.InternalStorage;
import org.flow.core.storages.StorageContext;
import org.flow.core.storages.StorageInterface;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatefulTriggerServiceTest {
    @Test
    void retainsTheCursorWithTenantIsolationAndExpiry() throws Exception {
        var storage = mock(StorageInterface.class);
        var objects = new HashMap<StorageKey, byte[]>();
        when(storage.put(any(), any(), any(), any(InputStream.class))).thenAnswer(call -> {
            try (InputStream input = call.getArgument(3)) {
                objects.put(new StorageKey(call.getArgument(0), call.getArgument(1), call.getArgument(2)), input.readAllBytes());
            }
            return call.getArgument(2);
        });
        when(storage.get(any(), any(), any())).thenAnswer(call -> {
            byte[] value = objects.get(new StorageKey(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
            if (value == null) {
                throw new FileNotFoundException();
            }
            return new ByteArrayInputStream(value);
        });

        var first = triggerContext(storage, "tenant-a");
        var second = triggerContext(storage, "tenant-b");
        var entry = new StatefulTriggerService.Entry("remote-object", "9", Instant.now(), Instant.now());
        var key = "shared_trigger";
        StatefulTriggerService.writeState(first, key, Map.of(entry.uri(), entry), Optional.of(Duration.ofDays(1)));

        var firstState = StatefulTriggerService.readState(first, key, Optional.of(Duration.ofHours(1)));
        assertThat(firstState).containsEntry(entry.uri(), entry);
        assertThat(StatefulTriggerService.shouldFire(firstState.get(entry.uri()), entry.version(), StatefulTriggerInterface.On.CREATE_OR_UPDATE)).isFalse();
        assertThat(StatefulTriggerService.readState(second, key, Optional.empty())).isEmpty();

        StatefulTriggerService.writeState(second, key, Map.of(entry.uri(), entry), Optional.of(Duration.ZERO));
        assertThat(StatefulTriggerService.readState(second, key, Optional.empty())).isEmpty();
        assertThat(StatefulTriggerService.readState(first, key, Optional.empty())).containsEntry(entry.uri(), entry);
    }

    private RunContext triggerContext(StorageInterface storage, String tenant) {
        var flow = Flow.builder().tenantId(tenant).namespace("test.cursor").id("sample").build();
        RunContext run = mock(RunContext.class);
        when(run.flowInfo()).thenReturn(new RunContext.FlowInfo(tenant, flow.getNamespace(), flow.getId(), 1));
        when(run.storage()).thenReturn(new InternalStorage(StorageContext.forFlow(flow), storage));
        return run;
    }

    private record StorageKey(String tenant, String namespace, URI uri) {
    }
}
