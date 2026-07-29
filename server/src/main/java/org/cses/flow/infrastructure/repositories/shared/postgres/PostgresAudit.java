package org.cses.flow.infrastructure.repositories.shared.postgres;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.paas.json.JsonObject;
import org.paas.session.Session;
import org.paas.session.User;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class PostgresAudit {

    private PostgresAudit() {
    }

    public static JSONB currentActor(DSLContext dsl) {
        Object value = dsl.configuration().data(Session.class);
        if (!(value instanceof Session<?> session)) {
            throw new IllegalStateException(
                "PostgreSQL write requires the current Session"
            );
        }
        User user = session.getUser();
        String actorId = firstText(
            user == null ? null : user.getId(),
            session.getUserId(),
            session.getId()
        );
        if (actorId == null) {
            throw new IllegalStateException(
                "PostgreSQL write requires a Session user id"
            );
        }
        JsonObject actor = JsonObject.Create("id", actorId);
        String actorName = firstText(
            user == null ? null : user.getName(),
            session.getUserName()
        );
        if (actorName != null) {
            actor.put("name", actorName);
        }
        return JSONB.valueOf(actor.toJson());
    }

    public static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
