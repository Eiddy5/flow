package org.flow.core.jdbc.repository;

import java.util.List;
import java.util.Map;

import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import org.flow.core.exceptions.InvalidQueryFiltersException;
import org.flow.core.models.QueryFilter;
import org.flow.gen.flow.Tables;
import org.flow.gen.flow.enums.StateType;
import org.flow.gen.flow.tables.ExecutionsTable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbstractJdbcRepositoryTest extends AbstractJdbcRepository {
    private Table<?> generatedTable = Tables.EXECUTIONS;

    @Override
    protected Table<?> table() {
        return generatedTable;
    }

    @Test
    void defaultConditions() {
        Map<QueryFilter.Field, Field<String>> columns = Map.of(
            QueryFilter.Field.NAMESPACE, Tables.EXECUTIONS.NAMESPACE,
            QueryFilter.Field.FLOW_ID, Tables.EXECUTIONS.FLOW_ID,
            QueryFilter.Field.ID, Tables.EXECUTIONS.ID,
            QueryFilter.Field.KIND, Tables.EXECUTIONS.KIND,
            QueryFilter.Field.PARENT_ID, Tables.EXECUTIONS.PARENT_ID,
            QueryFilter.Field.TRIGGER_EXECUTION_ID, Tables.EXECUTIONS.TRIGGER_EXECUTION_ID,
            QueryFilter.Field.TRIGGER_ID, Tables.EXECUTIONS.TRIGGER_ID
        );
        columns.forEach((field, column) -> {
            String assertValue = "anyValue";
            assertThat(this.getColumnField(field)).isSameAs(column);
            assertThat(this.getConditionOnField(field, assertValue, QueryFilter.Op.EQUALS, null))
                .isEqualTo(column.eq(assertValue));
            assertThat(this.getConditionOnField(field, assertValue, QueryFilter.Op.NOT_EQUALS, null))
                .isEqualTo(column.ne(assertValue));
            assertThat(this.getConditionOnField(field, assertValue, QueryFilter.Op.GREATER_THAN, null))
                .isEqualTo(column.greaterThan(assertValue));
            assertThat(this.getConditionOnField(field, assertValue, QueryFilter.Op.LESS_THAN, null))
                .isEqualTo(column.lessThan(assertValue));
            assertThat(this.getConditionOnField(field, List.of(assertValue), QueryFilter.Op.IN, null))
                .isEqualTo(column.in(List.of(assertValue)));
            assertThat(this.getConditionOnField(field, List.of(assertValue), QueryFilter.Op.NOT_IN, null))
                .isEqualTo(column.notIn(List.of(assertValue)));
            assertThat(this.getConditionOnField(field, assertValue, QueryFilter.Op.STARTS_WITH, null))
                .isEqualTo(column.startsWith(assertValue));
            assertThat(this.getConditionOnField(field, assertValue, QueryFilter.Op.ENDS_WITH, null))
                .isEqualTo(column.endsWith(assertValue));
            assertThat(this.getConditionOnField(field, assertValue, QueryFilter.Op.CONTAINS, null))
                .isEqualTo(column.contains(assertValue));
            assertThat(this.getConditionOnField(field, assertValue, QueryFilter.Op.REGEX, null))
                .isEqualTo(column.likeRegex(assertValue));
            assertThat(this.getConditionOnField(field, assertValue, QueryFilter.Op.PREFIX, null))
                .isEqualTo(column.eq(assertValue).or(column.startsWith(assertValue + ".")));
        });
    }

    @Test
    void shouldKeepConfiguredGeneratedFieldIdentity() {
        ExecutionsTable configuredTable = Tables.EXECUTIONS.rename(DSL.name("flow_schema", "Execution Rows"));
        generatedTable = configuredTable;

        assertThat(getColumnField(QueryFilter.Field.NAMESPACE)).isSameAs(configuredTable.NAMESPACE);
        assertThat(keyField()).isSameAs(configuredTable.KEY);
        assertThat(valueField()).isSameAs(configuredTable.VALUE);
        assertThat(tenantIdField()).isSameAs(configuredTable.TENANT_ID);
        assertThat(deletedField()).isSameAs(configuredTable.DELETED);
        assertThat(getConditionOnField(QueryFilter.Field.NAMESPACE, "team", QueryFilter.Op.EQUALS, null))
            .isEqualTo(configuredTable.NAMESPACE.eq("team"));
    }

    @Test
    void shouldBindNumericFiltersUsingGeneratedColumnType() {
        generatedTable = Tables.LOGS;

        assertThat(getColumnField(QueryFilter.Field.ATTEMPT_NUMBER)).isSameAs(Tables.LOGS.ATTEMPT_NUMBER);
        assertThat(getConditionOnField(QueryFilter.Field.ATTEMPT_NUMBER, "2", QueryFilter.Op.EQUALS, null))
            .isEqualTo(Tables.LOGS.ATTEMPT_NUMBER.eq(2));
        assertThat(getConditionOnField(QueryFilter.Field.ATTEMPT_NUMBER, List.of("1", "2"), QueryFilter.Op.IN, null))
            .isEqualTo(Tables.LOGS.ATTEMPT_NUMBER.in(1, 2));
    }

    @Test
    void shouldBindStateUsingGeneratedEnum() {
        assertThat(getConditionOnField(QueryFilter.Field.STATE, "SUCCESS", QueryFilter.Op.EQUALS, null))
            .isEqualTo(Tables.EXECUTIONS.STATE_CURRENT.in(StateType.SUCCESS));
    }

    @Test
    void shouldRejectFieldsAbsentFromGeneratedTable() {
        assertThatThrownBy(() -> getColumnField(QueryFilter.Field.ASSET_ID))
            .isInstanceOf(InvalidQueryFiltersException.class)
            .hasMessageContaining("Unsupported field: ASSET_ID");
        assertThatThrownBy(() -> getConditionOnField(QueryFilter.Field.TAGS, List.of("my-tag"), QueryFilter.Op.IN, null))
            .isInstanceOf(InvalidQueryFiltersException.class)
            .hasMessageContaining("Unsupported field: TAGS");
    }

    @Test
    void shouldUseIsNullWhenEqualsValueIsNull() {
        // Given
        Field<String> column = Tables.EXECUTIONS.NAMESPACE;

        // When / Then — EQUALS null must generate IS NULL, not col = NULL
        assertThat(this.getConditionOnField(QueryFilter.Field.NAMESPACE, null, QueryFilter.Op.EQUALS, null))
            .isEqualTo(column.isNull());

        // When / Then — NOT_EQUALS null must generate IS NOT NULL
        assertThat(this.getConditionOnField(QueryFilter.Field.NAMESPACE, null, QueryFilter.Op.NOT_EQUALS, null))
            .isEqualTo(column.isNotNull());
    }

    @Test
    void shouldThrowWhenNullValueIsUsedWithGreaterThanOrLessThan() {
        // Given / When / Then
        assertThatThrownBy(() -> this.getConditionOnField(QueryFilter.Field.NAMESPACE, null, QueryFilter.Op.GREATER_THAN, null))
            .isInstanceOf(InvalidQueryFiltersException.class)
            .hasMessageContaining("GREATER_THAN operation requires a non-null value");

        assertThatThrownBy(() -> this.getConditionOnField(QueryFilter.Field.NAMESPACE, null, QueryFilter.Op.LESS_THAN, null))
            .isInstanceOf(InvalidQueryFiltersException.class)
            .hasMessageContaining("LESS_THAN operation requires a non-null value");
    }

    @Test
    void shouldThrowWhenNullValueIsUsedWithInOrNotIn() {
        // Given / When / Then
        assertThatThrownBy(() -> this.getConditionOnField(QueryFilter.Field.NAMESPACE, null, QueryFilter.Op.IN, null))
            .isInstanceOf(InvalidQueryFiltersException.class)
            .hasMessageContaining("IN operation requires a non-null value");

        assertThatThrownBy(() -> this.getConditionOnField(QueryFilter.Field.NAMESPACE, null, QueryFilter.Op.NOT_IN, null))
            .isInstanceOf(InvalidQueryFiltersException.class)
            .hasMessageContaining("NOT_IN operation requires a non-null value");
    }

    @Test
    void shouldThrowWhenListValueIsUsedWithStartsWith() {
        List<String> invalidValue = List.of("val1", "val2");

        assertThatThrownBy(
            () -> this.getConditionOnField(
                QueryFilter.Field.NAMESPACE,
                invalidValue,
                QueryFilter.Op.STARTS_WITH,
                null
            )
        )
            .isInstanceOf(InvalidQueryFiltersException.class)
            .hasMessageContaining("STARTS_WITH operation requires a string value, got a List");
    }

    @Test
    void shouldEscapeWildcardCharactersInLikeOperations() {
        // Given — a value containing SQL LIKE metacharacters
        String wildcardValue = "%";
        String underscoreValue = "_";
        Field<String> column = Tables.EXECUTIONS.NAMESPACE;

        // When / Then — CONTAINS: metacharacter must be escaped, not treated as a wildcard
        assertThat(this.getConditionOnField(QueryFilter.Field.NAMESPACE, wildcardValue, QueryFilter.Op.CONTAINS, null))
            .isEqualTo(column.contains(wildcardValue));

        // When / Then — STARTS_WITH: % in value must not produce an open-ended match
        assertThat(this.getConditionOnField(QueryFilter.Field.NAMESPACE, wildcardValue, QueryFilter.Op.STARTS_WITH, null))
            .isEqualTo(column.startsWith(wildcardValue));

        // When / Then — ENDS_WITH: _ in value must not act as a single-char wildcard
        assertThat(this.getConditionOnField(QueryFilter.Field.NAMESPACE, underscoreValue, QueryFilter.Op.ENDS_WITH, null))
            .isEqualTo(column.endsWith(underscoreValue));
    }

}
