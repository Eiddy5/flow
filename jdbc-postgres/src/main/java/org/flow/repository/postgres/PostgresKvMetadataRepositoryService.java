package org.flow.repository.postgres;

import static org.flow.gen.flow.Tables.KV_METADATA;

import java.util.ArrayList;
import java.util.List;

import org.jooq.Condition;
import org.jooq.impl.DSL;

import org.flow.core.models.kv.PersistedKvMetadata;
import org.flow.jdbc.AbstractJdbcRepository;

public abstract class PostgresKvMetadataRepositoryService {
    /**
     * 使用当前仓储生成表的全文字段构造筛选。
     *
     * @param jdbcRepository 当前仓储，提供实际配置的表
     * @param query 全文查询文字，可为 null
     * @return 全文筛选条件
     */
    public static Condition findCondition(AbstractJdbcRepository<PersistedKvMetadata> jdbcRepository, String query) {
        List<Condition> conditions = new ArrayList<>();

        if (query != null) {
            conditions.add(jdbcRepository.fullTextCondition(List.of(jdbcRepository.getTable(KV_METADATA).FULLTEXT), query));
        }

        return conditions.isEmpty() ? DSL.noCondition() : DSL.and(conditions);
    }
}
