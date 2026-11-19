package org.flow.core.models.triggers;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import com.google.common.hash.Hashing;

import org.flow.core.runners.RunContext;
import org.flow.core.serializers.JacksonMapper;
import org.flow.core.storages.StorageContext;

public class StatefulTriggerService {
    public record Entry(String uri, String version, Instant modifiedAt, Instant lastSeenAt) {
        public static Entry candidate(String uri, String version, Instant modifiedAt) {
            return new Entry(uri, version, modifiedAt, null);
        }
    }

    public record StateUpdate(boolean fire, boolean isNew) {
    }

    record Cursor(Instant expiresAt, List<Entry> entries) {
    }

    public static Map<String, Entry> readState(RunContext runContext, String key, Optional<Duration> ttl) {
        try (var input = runContext.storage().getFile(stateUri(runContext.flowInfo().namespace(), key))) {
            Cursor cursor = JacksonMapper.ofJson().readValue(input, Cursor.class);
            Instant now = Instant.now();
            if (cursor.expiresAt() != null && !cursor.expiresAt().isAfter(now)) {
                return new HashMap<>();
            }
            Instant cutoff = ttl.map(now::minus).orElse(Instant.MIN);
            return cursor.entries().stream()
                .filter(e -> Optional.ofNullable(e.lastSeenAt()).orElse(now).isAfter(cutoff))
                .collect(Collectors.toMap(Entry::uri, e -> e));
        } catch (FileNotFoundException | NoSuchFileException e) {
            return new HashMap<>();
        } catch (Exception e) {
            runContext.logger().warn("Failed to read state for trigger cursor {}", key, e);
            return new HashMap<>();
        }
    }

    public static void writeState(RunContext runContext, String key, Map<String, Entry> state, Optional<Duration> ttl) {
        try {
            Cursor cursor = new Cursor(ttl.map(Instant.now()::plus).orElse(null), List.copyOf(state.values()));
            var bytes = JacksonMapper.ofJson().writeValueAsBytes(cursor);
            runContext.storage().putFile(new ByteArrayInputStream(bytes), stateUri(runContext.flowInfo().namespace(), key));
        } catch (Exception e) {
            runContext.logger().warn("Failed to write state for trigger cursor {}", key, e);
        }
    }

    private static URI stateUri(String namespace, String key) {
        String digest = Hashing.sha256().hashString(key, StandardCharsets.UTF_8).toString();
        return URI.create(StorageContext.namespaceRootUri(namespace) + "_trigger_state/" + digest + ".json");
    }

    public static StateUpdate computeAndUpdateState(Map<String, Entry> state, Entry candidate, StatefulTriggerInterface.On on) {
        var prev = state.get(candidate.uri());
        var isNew = prev == null;
        var fire = shouldFire(prev, candidate.version(), on);

        Instant lastSeenAt;

        if (fire || isNew) {
            // it is new seen or changed
            lastSeenAt = Instant.now();
        } else if (prev.lastSeenAt() != null) {
            // it is unchanged but already tracked before
            lastSeenAt = prev.lastSeenAt();
        } else {
            lastSeenAt = Instant.now();
        }

        var newEntry = new Entry(candidate.uri(), candidate.version(), candidate.modifiedAt(), lastSeenAt);

        state.put(candidate.uri(), newEntry);

        return new StatefulTriggerService.StateUpdate(fire, isNew);
    }

    public static boolean shouldFire(Entry prev, String version, StatefulTriggerInterface.On on) {
        if (prev == null) {
            return on == StatefulTriggerInterface.On.CREATE || on == StatefulTriggerInterface.On.CREATE_OR_UPDATE;
        }
        if (!Objects.equals(prev.version(), version)) {
            return on == StatefulTriggerInterface.On.UPDATE || on == StatefulTriggerInterface.On.CREATE_OR_UPDATE;
        }
        return false;
    }

    public static String defaultKey(String ns, String flowId, String triggerId) {
        return String.join("_", ns, flowId, triggerId);
    }
}
