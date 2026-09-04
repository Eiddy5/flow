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
public class NoopWebsocketRepository
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

    /**
     * Reports that the no-op repository did not delete a current session.
     *
     * @param session session candidate supplied by the WebSocket component;
     *                must not be modified
     * @return always {@code false} because this test replacement stores no
     *         sessions
     */
    @Override
    public boolean deleteIfCurrent(SessionObject session) {
        return false;
    }

    /**
     * Accepts a subscription request without persisting it.
     *
     * @param session current session supplied by the WebSocket component;
     *                must not be modified
     * @param subscription subscription supplied by the WebSocket component;
     *                     must not be modified
     */
    @Override
    public void ensureSubscription(
        SessionObject session,
        WebsocketSubscription subscription
    ) {
    }

    /**
     * Accepts an unsubscribe request without persisting it.
     *
     * @param session current session supplied by the WebSocket component;
     *                must not be modified
     * @param subscription subscription supplied by the WebSocket component;
     *                     must not be modified
     */
    @Override
    public void unsubscribeIfCurrent(
        SessionObject session,
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
