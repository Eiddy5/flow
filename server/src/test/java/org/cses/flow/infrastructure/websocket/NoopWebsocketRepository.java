package org.cses.flow.infrastructure.websocket;

import io.micronaut.context.annotation.Primary;
import jakarta.inject.Singleton;
import org.paas.websocket.config.SessionType;
import org.paas.websocket.entry.SessionObject;
import org.paas.websocket.entry.WebsocketSubscription;
import org.paas.websocket.index.SessionSearchResult;
import org.paas.websocket.index.WebsocketRepository;

import java.util.Collection;

/**
 * Test-only replacement for the PAAS database-backed WebSocket repository.
 */
@Primary
@Singleton
public final class NoopWebsocketRepository
    implements WebsocketRepository {

    @Override
    public SessionSearchResult search(
        WebsocketSubscription subscription,
        SessionType sessionType,
        Collection<String> ids,
        int limit,
        int offset
    ) {
        return new SessionSearchResult();
    }

    @Override
    public void insert(SessionObject session) {
    }

    @Override
    public void delete(String deviceId) {
    }

    @Override
    public void subscribe(
        String deviceId,
        WebsocketSubscription subscription
    ) {
    }

    @Override
    public void unsubscribe(
        String deviceId,
        WebsocketSubscription subscription
    ) {
    }

    @Override
    public int totalCount() {
        return 0;
    }

    @Override
    public void deleteByServerName(String serverName) {
    }
}
