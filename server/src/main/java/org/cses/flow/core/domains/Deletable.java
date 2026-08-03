package org.cses.flow.core.domains;

public interface Deletable<T> {
    boolean isDeleted();

    T delete();
}
