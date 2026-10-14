package org.flow.repository.postgres;

import java.util.ArrayList;
import java.util.List;

import org.flow.core.models.QueryFilter;
import org.flow.core.models.executions.LogEntry;
import org.flow.core.repositories.PaginationType;

import io.micronaut.data.model.CursoredPage;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.swagger.v3.oas.annotations.Hidden;

/**
 * Test-only log store with a reduced capability set, mirroring a cloud log store such as GCP Cloud
 * Logging: it can ingest, read and filter logs, but cannot purge, and paginates by a
 * stateless forward cursor (no exact total) rather than by offset.
 * <p>
 * It only declares its capabilities — {@code canPurge} → false and
 * {@link #paginationType()} → CURSOR — and returns a {@link CursoredPage} from {@link #find}. Everything
 * else (writes, reads, and the graceful degradation of purge → no-op) is inherited
 * from {@link PostgresLogDataStore}/{@code AbstractJdbcLogDataStore}, which honor those flags. This is exactly the
 * contract a real external store must satisfy, so the shared {@code AbstractLogDataStoreTest} exercises every
 * branch against it.
 * <p>
 * Wired via {@code @MockBean(LogDataStoreInterface.class)} in the tests (with {@code flow.logs.type} unset so it
 * falls back to the Postgres repository — which keeps the log-table migrations, e.g. the {@code task_id} widen, running).
 */
// @Plugin and @Plugin.Id are @Inherited, so without @Hidden this test double would be registered as a
// SECOND log-store plugin under the inherited id "postgres". LogDataStoreInterfaceFactory.resolve("postgres") then does
// a findFirst() over all types with that id and non-deterministically instantiates PostgresLogDataStore OR this
// reduced store (canPurge()==false) in unrelated suites — e.g. PostgresExecutionServiceTest.purge() intermittently
// deleting 0 logs. @Hidden makes the plugin scanner skip it, so it is only ever wired via the @MockBean below.
@Hidden
public class ReducedLogDataStore extends PostgresLogDataStore {

    @Override
    public boolean canPurge() {
        return false;
    }

    @Override
    public PaginationType paginationType() {
        return PaginationType.CURSOR;
    }

    /**
     * Cursor pagination over the inherited Postgres rows: the cursor is a single opaque token the store defines —
     * here the absolute offset of the next page as a string. Decode it, fetch that window with the real query,
     * and expose the next-page offset (offset + page size, a full stride so it stays page-aligned) as the cursor.
     * Total is {@code null} (cursor mode). Assumes a constant page size across the walk, which is fine for a fixture.
     */
    @Override
    public Page<LogEntry> find(Pageable pageable, String tenantId, List<QueryFilter> filters) {
        int size = pageable.isUnpaged() ? Integer.MAX_VALUE : pageable.getSize();
        long offset = pageable.cursor()
            .map(cursor -> Long.parseLong((String) cursor.get(0)))
            .orElse(0L);

        Pageable offsetPageable = pageable.isUnpaged()
            ? Pageable.UNPAGED
            : Pageable.from((int) (offset / size) + 1, size, pageable.getSort());

        List<LogEntry> content = super.find(offsetPageable, tenantId, filters).getContent();

        // One cursor per row (Micronaut expects the list to match the content); every entry carries the same
        // next-page token, so the last one — which the wrapper reads — resumes at the next page.
        String nextToken = String.valueOf(offset + size);
        List<Pageable.Cursor> cursors = new ArrayList<>(content.size());
        for (int i = 0; i < content.size(); i++) {
            cursors.add(Pageable.Cursor.of(nextToken));
        }

        return CursoredPage.of(content, pageable, cursors, null);
    }
}
