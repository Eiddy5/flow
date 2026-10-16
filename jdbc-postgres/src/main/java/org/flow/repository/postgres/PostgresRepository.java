package org.flow.repository.postgres;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.InsertOnDuplicateSetMoreStep;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.RecordMapper;
import org.jooq.Result;
import org.jooq.SelectConditionStep;
import org.jooq.impl.DSL;

import org.flow.core.repositories.ArrayListTotal;
import org.flow.jdbc.JdbcJsonbUtils;
import org.flow.jdbc.JdbcTableConfig;
import org.flow.jdbc.JooqDSLContextWrapper;

import io.micronaut.context.annotation.EachBean;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.condition.ConditionContext;
import io.micronaut.data.model.Pageable;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import lombok.SneakyThrows;




@Requires(condition = PostgresRepository.PostgresCondition.class)
@EachBean(JdbcTableConfig.class)
public class PostgresRepository<T> extends org.flow.jdbc.AbstractJdbcRepository<T> {

    @Inject
    public PostgresRepository(@Parameter JdbcTableConfig jdbcTableConfig,
        JooqDSLContextWrapper dslContextWrapper) {
        super(jdbcTableConfig, dslContextWrapper);
    }

    @Override
    public Condition fullTextCondition(List<? extends Field<?>> fields, String query) {
        if (query == null || query.equals("*")) {
            return DSL.noCondition();
        }

        if (fields.size() > 1) {
            throw new IllegalArgumentException("Invalid fullTextCondition" + fields);
        }

        return DSL.condition("{0} @@ FULLTEXT_SEARCH({1})", fields.getFirst(), DSL.val(query));
    }

    @SneakyThrows
    @Override
    public Map<Field<?>, Object> persistFields(T entity) {
        String json = MAPPER.writeValueAsString(entity);
        Map<Field<?>, Object> fields = HashMap.newHashMap(1);
        fields.put(valueField(), JdbcJsonbUtils.valueOf(json));
        return fields;
    }

    @SneakyThrows
    @Override
    public void persist(T entity, DSLContext context, @Nullable Map<Field<?>, Object> fields) {
        Map<Field<?>, Object> finalFields = fields == null ? this.persistFields(entity) : fields;

        context
            .insertInto(table)
            .set(keyField(), key(entity))
            .set(finalFields)
            .onConflict(keyField())
            .doUpdate()
            .set(excluded(finalFields))
            .execute();
    }

    @Override
    protected InsertOnDuplicateSetMoreStep<Record> buildInsertRequest(T entity, Map<Field<?>, Object> fields,
        DSLContext dslContext) {

        return dslContext
            .insertInto(table)
            .set(keyField(), key(entity))
            .set(fields)
            .onConflict(keyField())
            .doUpdate()
            .set(excluded(fields));
    }

    @SuppressWarnings("unchecked")
    @Override
    public <R extends Record, E> ArrayListTotal<E> fetchPage(DSLContext context, SelectConditionStep<R> select, Pageable pageable, RecordMapper<R, E> mapper) {
        Result<Record> results = this.limit(
            context.select(DSL.asterisk(), DSL.count().over().as("total_count"))
                .from(
                    this
                        .sort(select, pageable)
                        .asTable("page")
                )
                .where(DSL.noCondition()),
            pageable
        )
            .fetch();

        Integer totalCount = !results.isEmpty() ? results.getFirst().get("total_count", Integer.class) : 0;

        List<E> map = results
            .map((Record record) -> mapper.map((R) record));

        return new ArrayListTotal<>(map, totalCount);
    }

    @Override
    public <R extends Record> T map(R record) {
        if (deserializer != null) {
            return deserializer.apply(record);
        } else {
            return this.deserialize(record.get(valueField()).data());
        }
    }

    // We need to create PostgreSQL repositories for the queue as it uses a repository named 'queue',
    // we may find a way to only create this one at some point as here we create unnecessary beans.
    static class PostgresCondition implements io.micronaut.context.condition.Condition {
        @Override
        public boolean matches(ConditionContext context) {
            boolean isRepository = ((Optional<String>) context.get("flow.repository.type", String.class)).map(it -> "postgres".equals(it)).orElse(false);
            boolean isQueue = ((Optional<String>) context.get("flow.queue.type", String.class)).map(it -> "postgres".equals(it)).orElse(false);
            return isRepository || isQueue;
        }
    }
}
