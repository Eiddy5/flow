package org.flow.jdbc;

import java.util.Map;

import io.micronaut.core.value.MapPropertyResolver;
import org.flow.gen.flow.Tables;
import org.flow.gen.flow.tables.ExecutionsTable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcTableConfigTest {
    /** 验证局部物理表名覆盖保留生成字段类型，并且不会取消其他表的默认绑定。 */
    @Test
    void partialOverrideKeepsGeneratedTypesAndOtherDefaults() {
        var properties = new MapPropertyResolver(Map.of(
            "flow.jdbc.tables.executions.table", "\"tenant schema\".\"execution rows\""));
        var executions = JdbcTableConfig.configured("executions", Object.class, Tables.EXECUTIONS, properties);
        var flows = JdbcTableConfig.configured("flows", Object.class, Tables.FLOWS, properties);

        assertThat(executions.jooqTable()).isInstanceOf(ExecutionsTable.class);
        var table = (ExecutionsTable) (Object) executions.jooqTable();
        assertThat(table.getQualifiedName().getName()).containsExactly("tenant schema", "execution rows");
        assertThat(table.getSchema().getName()).isEqualTo("tenant schema");
        assertThat(table.VALUE.getTable()).isSameAs(table);
        assertThat(table.VALUE.getDataType()).isEqualTo(Tables.EXECUTIONS.VALUE.getDataType());
        assertThat(flows.jooqTable()).isSameAs(Tables.FLOWS);
    }
}
