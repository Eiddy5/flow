package org.flow.core.models.tasks;

import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import org.flow.core.models.flows.State;
import org.flow.core.serializers.JacksonMapper;

public interface Output {
    default Optional<State.Type> finalState() {
        return Optional.empty();
    }

    default Map<String, Object> toMap() {
        return JacksonMapper.toMap(this);
    }

    default Map<String, Object> toMap(ZoneId zoneId) {
        return JacksonMapper.toMap(this, zoneId);
    }
}
