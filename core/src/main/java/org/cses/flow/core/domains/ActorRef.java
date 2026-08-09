package org.cses.flow.core.domains;

import org.paas.session.Session;
import org.paas.session.User;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable value object identifying the actor responsible for a domain
 * change.
 */
public final class ActorRef {

    private final String id;
    private final String name;

    private ActorRef(String id, String name) {
        this.id = requireText(id, "Actor id");
        this.name = normalizeOptionalText(name);
    }

    public static ActorRef create(String id, String name) {
        return new ActorRef(id, name);
    }

    public static ActorRef from(Session<? extends User> session) {
        Objects.requireNonNull(session, "Session");
        User user = session.getUser();
        String actorId = firstText(
            user == null ? null : user.getId(),
            session.getUserId(),
            session.getId()
        );
        if (actorId == null) {
            throw new IllegalArgumentException(
                "Session actor id must not be blank"
            );
        }
        String actorName = firstText(
            user == null ? null : user.getName(),
            user == null ? null : session.getUserName()
        );
        return new ActorRef(actorId, actorName);
    }

    public static ActorRef rehydrate(String id, String name) {
        return new ActorRef(id, name);
    }

    public String id() {
        return id;
    }

    public Optional<String> name() {
        return Optional.ofNullable(name);
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (!(value instanceof ActorRef other)) {
            return false;
        }
        return Objects.equals(id, other.id)
            && Objects.equals(name, other.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name);
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeOptionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
