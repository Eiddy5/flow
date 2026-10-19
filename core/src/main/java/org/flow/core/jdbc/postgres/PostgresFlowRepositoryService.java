package org.flow.core.jdbc.postgres;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.DSL;

import org.flow.core.exceptions.InvalidQueryFiltersException;
import org.flow.core.models.QueryFilter;
import org.flow.core.models.flows.FlowInterface;
import org.flow.core.jdbc.AbstractJdbcRepository;

import static org.flow.core.models.QueryFilter.Op.EQUALS;
import static org.flow.gen.flow.Tables.FLOWS;
import static org.flow.core.jdbc.postgres.PostgresRepositoryUtils.*;

public abstract class PostgresFlowRepositoryService {
    /**
     * 使用当前流程表的生成字段构造全文及标签查询。
     *
     * @param jdbcRepository 当前流程仓储，提供实际配置的表
     * @param query 全文查询文字，可为 null
     * @param labels 完整匹配的标签集合，可为 null
     * @return 合并后的查询条件
     */
    public static Condition findCondition(AbstractJdbcRepository<? extends FlowInterface> jdbcRepository, String query, Map<String, String> labels) {
        var table = jdbcRepository.getTable(FLOWS);
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
     * 使用当前流程表的源码生成字段构造全文查询。
     *
     * @param jdbcRepository 当前流程仓储，提供实际配置的表
     * @param query 全文查询文字
     * @return 源码全文查询条件
     */
    public static Condition findSourceCodeCondition(AbstractJdbcRepository<? extends FlowInterface> jdbcRepository, String query) {
        return jdbcRepository.fullTextCondition(
            List.of(DSL.field("FULLTEXT_INDEX({0})", Object.class, jdbcRepository.getTable(FLOWS).SOURCE_CODE)), query
        );
    }

    /**
     * 使用当前流程的 JSONB 生成字段匹配指定触发器类型。
     *
     * @param valueField 当前查询表的 JSONB 载荷字段
     * @param triggerClass 触发器类型，为 null 时匹配全部流程
     * @return 流程包含指定触发器时成立的条件
     */
    public static Condition findTriggerClassCondition(Field<JSONB> valueField, Class<? extends org.flow.core.models.triggers.AbstractTrigger> triggerClass) {
        if (triggerClass == null) {
            return DSL.trueCondition();
        }
        return DSL.condition(
            "jsonb_path_exists({0}, '$.triggers[*] ? (@.type == $triggerType)', jsonb_build_object('triggerType', {1}::text))",
            valueField, DSL.val(triggerClass.getName(), String.class)
        );
    }

    /**
     * 使用查询表的 JSONB 生成字段构造标签筛选，保留空值与集合操作语义。
     *
     * @param valueField 当前查询表的 JSONB 载荷字段
     * @param labels 标签键值集合或标签查询文字
     * @param operation 已验证的筛选操作
     * @return 合并后的标签条件
     */
    public static Condition findCondition(Field<JSONB> valueField, Object labels, QueryFilter.Op operation) {
        List<Condition> conditions = new ArrayList<>();
        List<Condition> inConditions = new ArrayList<>();

        if (labels instanceof String label) {
            switch (operation) {
                case CONTAINS -> conditions.add(labelContainsCondition(valueField, label));
                case NOT_CONTAINS -> conditions.add(labelContainsCondition(valueField, label).not());
                case IS_NULL -> conditions.add(labelKeyCondition(valueField, label).not());
                case IS_NOT_NULL -> conditions.add(labelKeyCondition(valueField, label));
                default -> throw new InvalidQueryFiltersException("Unsupported operation: " + operation);
            }
        } else if (labels instanceof Map<?, ?> labelValues) {
            labelValues.forEach((key, value) ->
            {
                Field<Boolean> labelMatches = labelMatches(valueField, (String) key, (String) value);
                if (operation.equals(EQUALS)) {
                    conditions.add(labelMatches.isTrue());
                } else if (operation.equals(QueryFilter.Op.IN)) {
                    inConditions.add(labelMatches.isTrue());
                } else if (operation.equals(QueryFilter.Op.NOT_IN)) {
                    conditions.add(labelMatches.isFalse());
                } else if (operation.equals(QueryFilter.Op.NOT_EQUALS)) {
                    conditions.add(labelMatches.isFalse());
                } else if (operation.equals(QueryFilter.Op.CONTAINS)) {
                    conditions.add(labelValueContainsCondition(valueField, (String) key, (String) value));
                } else if (operation.equals(QueryFilter.Op.NOT_CONTAINS)) {
                    conditions.add(labelValueContainsCondition(valueField, (String) key, (String) value).not());
                } else if (operation.equals(QueryFilter.Op.IS_NULL)) {
                    conditions.add(labelKeyCondition(valueField, (String) key).not());
                } else if (operation.equals(QueryFilter.Op.IS_NOT_NULL)) {
                    conditions.add(labelKeyCondition(valueField, (String) key));
                } else {
                    throw new InvalidQueryFiltersException("Unsupported operation: " + operation);
                }
            });
        }
        if (!inConditions.isEmpty()) {
            conditions.add(DSL.or(inConditions));
        }
        return conditions.isEmpty() ? DSL.noCondition() : DSL.and(conditions);
    }

}
