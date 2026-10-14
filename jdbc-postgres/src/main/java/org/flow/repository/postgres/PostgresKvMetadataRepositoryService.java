package org.flow.repository.postgres;

import java.util.ArrayList;
import java.util.List;

import org.jooq.Condition;
import org.jooq.impl.DSL;

import org.flow.core.models.kv.PersistedKvMetadata;
import org.flow.jdbc.AbstractJdbcRepository;

public abstract class PostgresKvMetadataRepositoryService {
    public static Condition findCondition(AbstractJdbcRepository<PersistedKvMetadata> jdbcRepository, String query) {
        List<Condition> conditions = new ArrayList<>();

        if (query != null) {
            conditions.add(jdbcRepository.fullTextCondition(List.of("fulltext"), query));
        }

        return conditions.isEmpty() ? DSL.noCondition() : DSL.and(conditions);
    }
}
