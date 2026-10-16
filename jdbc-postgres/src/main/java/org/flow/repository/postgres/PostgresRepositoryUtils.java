package org.flow.repository.postgres;

import java.sql.Timestamp;

import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.DSL;


public class PostgresRepositoryUtils {
    /** 禁止创建仅提供 PostgreSQL 查询表达式的工具实例。 */
    private PostgresRepositoryUtils() {
        // utility class pattern
    }

    /**
     * 将传入的时间字段转换为 UTC 墙上时间，供日期分组使用；保留字段所属的实际表或查询别名。
     *
     * @param dateField 查询使用的时间字段，不能为 {@code null}
     * @return 不受 PostgreSQL 会话时区影响的时间表达式
     */
    public static Field<Timestamp> utcTimestampField(Field<?> dateField) {
        return DSL.field("({0} AT TIME ZONE 'UTC')", Timestamp.class, dateField);
    }

    /**
     * 构造原生 JSONB 标签键值的完整匹配表达式，缺少标签时返回 false。
     *
     * @param valueField 当前查询表的 JSONB 载荷字段，不能为 {@code null}
     * @param key 需要匹配的标签键，不修改该值
     * @param value 需要匹配的标签值，不修改该值
     * @return 可用于肯定或否定条件的布尔表达式
     */
    public static Field<Boolean> labelMatches(Field<JSONB> valueField, String key, String value) {
        return DSL.field(
            "COALESCE({0} -> 'labels' @> jsonb_build_array(jsonb_build_object('key', {1}::text, 'value', {2}::text)), false)",
            Boolean.class, valueField, DSL.val(key, String.class), DSL.val(value, String.class)
        );
    }

    /**
     * 构造标签键或值包含查询文字的条件；查询按 PostgreSQL 的大小写不敏感 LIKE 规则执行。
     *
     * @param valueField 当前查询表的 JSONB 载荷字段，不能为 {@code null}
     * @param query 查询文字，不修改该值
     * @return 任一标签键或值匹配时成立的条件
     */
    public static Condition labelContainsCondition(Field<JSONB> valueField, String query) {
        return DSL.condition(
            "EXISTS (SELECT 1 FROM jsonb_array_elements(COALESCE({0} -> 'labels', '[]'::jsonb)) AS lbl"
                + " WHERE lower(lbl ->> 'value') LIKE lower('%' || {1} || '%')"
                + " OR lower(lbl ->> 'key') LIKE lower('%' || {1} || '%'))",
            valueField, DSL.val(query, String.class)
        );
    }

    /**
     * 构造指定标签键的值包含查询文字的条件，保留原生大小写不敏感 LIKE 语义。
     *
     * @param valueField 当前查询表的 JSONB 载荷字段，不能为 {@code null}
     * @param key 需要完整匹配的标签键，不修改该值
     * @param value 查询文字，不修改该值
     * @return 指定标签键存在且值匹配时成立的条件
     */
    public static Condition labelValueContainsCondition(Field<JSONB> valueField, String key, String value) {
        return DSL.condition(
            "EXISTS (SELECT 1 FROM jsonb_array_elements(COALESCE({0} -> 'labels', '[]'::jsonb)) AS lbl"
                + " WHERE lbl ->> 'key' = {1} AND lower(lbl ->> 'value') LIKE lower('%' || {2} || '%'))",
            valueField, DSL.val(key, String.class), DSL.val(value, String.class)
        );
    }

    /**
     * 构造原生 JSONB 载荷中存在指定标签键的条件。
     *
     * @param valueField 当前查询表的 JSONB 载荷字段，不能为 {@code null}
     * @param key 需要完整匹配的标签键，不修改该值
     * @return 指定标签键存在时成立的条件
     */
    public static Condition labelKeyCondition(Field<JSONB> valueField, String key) {
        return DSL.condition(
            "EXISTS (SELECT 1 FROM jsonb_array_elements(COALESCE({0} -> 'labels', '[]'::jsonb)) AS lbl"
                + " WHERE lbl ->> 'key' = {1})",
            valueField, DSL.val(key, String.class)
        );
    }
}
