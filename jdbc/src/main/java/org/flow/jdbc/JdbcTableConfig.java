package org.flow.jdbc;

import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.core.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import jakarta.inject.Inject;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;


@EachProperty("flow.jdbc.tables")
public class JdbcTableConfig {
    private String name;
    private Class<?> cls;
    private String table;
    private Table<Record> jooqTable;

    @Inject
    public JdbcTableConfig(
        @Parameter String name,
        @Nullable Class<?> cls,
        @NotNull String table) {
        this(name, cls, table, JdbcTableConfigsFactory.generatedTable(name));
    }

    /** Uses the generated relation shape without pinning its generation-time schema. */
    public JdbcTableConfig(String name, @Nullable Class<?> cls, Table<?> generatedTable) {
        this(name, cls, generatedTable.getName(), generatedTable);
    }

    /** Applies physical-name configuration through the generated table's own rename operation. */
    @SuppressWarnings("unchecked")
    public JdbcTableConfig(String name, @Nullable Class<?> cls, String table, Table<?> generatedTable) {
        if (generatedTable == null) {
            throw new IllegalArgumentException("No generated table registered for '" + name + "'");
        }
        this.name = name;
        this.cls = cls;
        this.table = table;
        var physicalName = DSL.using(SQLDialect.POSTGRES).parser().parseName(table);
        this.jooqTable = (Table<Record>) (generatedTable.getQualifiedName().equals(physicalName)
            ? generatedTable : ((org.jooq.impl.TableImpl<?>) generatedTable).rename(physicalName));
    }

    public Table<Record> jooqTable() {
        return this.jooqTable;
    }

    public String name() {
        return this.name;
    }

    public Class<?> cls() {
        return this.cls;
    }

    public String table() {
        return this.table;
    }
}
