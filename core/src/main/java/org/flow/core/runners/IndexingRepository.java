package org.flow.core.runners;

import java.util.List;

/**
 * This interface exposes methods used by the {@link org.flow.core.runners.Indexer}.
 * Only repositories that are indexed should implement this interface.
 *
 * @param <T> the entity type
 */
public interface IndexingRepository<T> {
    T save(T item);

    int saveBatch(List<T> items);
}
