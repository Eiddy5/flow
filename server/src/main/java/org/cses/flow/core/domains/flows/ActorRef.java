package org.cses.flow.core.domains.flows;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable reference to the actor responsible for a domain change.
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
