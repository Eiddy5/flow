package org.flow.core.server;

import java.time.LocalDateTime;

import org.flow.core.models.HasUID;
import org.flow.core.queues.event.BroadcastEvent;
import org.flow.core.utils.IdUtils;

public record ClusterEvent(String uid, EventType eventType, LocalDateTime eventDate, String message) implements HasUID, BroadcastEvent {

    public ClusterEvent(EventType eventType, LocalDateTime eventDate, String message) {
        this(IdUtils.create(), eventType, eventDate, message);
    }

    @Override
    public String key() {
        return uid;
    }

    public enum EventType {
        MAINTENANCE_ENTER,
        MAINTENANCE_EXIT,
        PLUGINS_SYNC_REQUESTED,
        KILL_SWITCH_SYNC_REQUESTED,
        WORKER_GROUP_SYNC_REQUESTED,
        WORKER_DISCONNECT_REQUESTED
    }
}
