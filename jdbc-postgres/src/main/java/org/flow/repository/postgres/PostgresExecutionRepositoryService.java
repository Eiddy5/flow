package org.flow.repository.postgres;

import static org.flow.gen.flow.Tables.EXECUTIONS;
import static org.flow.repository.postgres.PostgresRepositoryUtils.*;

import java.util.*;

import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.DSL;

import org.flow.core.exceptions.InvalidQueryFiltersException;
import org.flow.core.models.QueryFilter;
import org.flow.core.models.executions.Execution;
import org.flow.core.utils.Either;
import org.flow.jdbc.AbstractJdbcRepository;

public abstract class PostgresExecutionRepositoryService {
    /**
     * 使用当前执行表的生成字段构造全文及标签查询。
     *
     * @param jdbcRepository 当前执行仓储，提供实际配置的表
     * @param query 全文查询文字，可为 null
     * @param labels 完整匹配的标签集合，可为 null
     * @return 合并后的查询条件
     */
    public static Condition findCondition(AbstractJdbcRepository<Execution> jdbcRepository, String query, Map<String, String> labels) {
        var table = jdbcRepository.getTable(EXECUTIONS);
        List<Condition> conditions = new ArrayList<>();

        if (query != null) {
            conditions.add(jdbcRepository.fullTextCondition(List.of(table.FULLTEXT), query));
        }

        if (labels != null) {
            labels.forEach((key, value) ->
            {
                conditions.add(
                    labelMatches(table.VALUE, key, value).isTrue()
                );
            });
        }

        return conditions.isEmpty() ? DSL.noCondition() : DSL.and(conditions);
    }

    /**
     * 使用查询表的 JSONB 生成字段构造标签筛选，保留空值与集合操作语义。
     *
     * @param valueField 当前查询表的 JSONB 载荷字段
     * @param input 标签键值集合或标签查询文字
     * @param operation 已验证的筛选操作
     * @return 合并后的标签条件
     */
    public static Condition findLabelCondition(Field<JSONB> valueField, Either<Map<?, ?>, String> input, QueryFilter.Op operation) {
        List<Condition> conditions = new ArrayList<>();
        List<Condition> inConditions = new ArrayList<>();
        if (input.isRight()) {
            var query = input.right().get();
            switch (Objects.requireNonNull(operation)) {
                case CONTAINS -> conditions.add(labelContainsCondition(valueField, query));
                case NOT_CONTAINS -> conditions.add(labelContainsCondition(valueField, query).not());
                case IS_NULL -> conditions.add(labelKeyCondition(valueField, query).not());
                case IS_NOT_NULL -> conditions.add(labelKeyCondition(valueField, query));
                default -> throw new InvalidQueryFiltersException("Unsupported operation for query: " + operation);
            }
        } else {
            var labels = input.getLeft();
            labels.forEach((key, value) ->
            {
                Field<Boolean> labelMatches = labelMatches(valueField, (String) key, (String) value);
                switch (operation) {
                    case EQUALS -> conditions.add(labelMatches.isTrue());
                    case CONTAINS -> conditions.add(labelValueContainsCondition(valueField, (String) key, (String) value));
                    case NOT_CONTAINS -> conditions.add(labelValueContainsCondition(valueField, (String) key, (String) value).not());
                    case NOT_EQUALS, NOT_IN -> conditions.add(labelMatches.isFalse());
                    case IN -> inConditions.add(labelMatches.isTrue());
                    case IS_NULL -> conditions.add(labelKeyCondition(valueField, (String) key).not());
                    case IS_NOT_NULL -> conditions.add(labelKeyCondition(valueField, (String) key));
                    default -> throw new InvalidQueryFiltersException("Unsupported operation: " + operation);
                }
            });
        }

        if (!inConditions.isEmpty()) {
            conditions.add(DSL.or(inConditions));
        }
        return conditions.isEmpty() ? DSL.noCondition() : DSL.and(conditions);
    }

}
