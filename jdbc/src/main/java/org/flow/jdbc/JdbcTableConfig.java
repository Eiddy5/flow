package org.flow.jdbc;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.value.PropertyResolver;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;


public class JdbcTableConfig {
    private String name;
    private Class<?> cls;
    private String table;
    private Table<Record> jooqTable;

    /**
     * 使用所属模块显式提供的生成表建立默认绑定。
     * @param name 表配置名称；不能为 null
     * @param cls 领域类型；可为 null
     * @param generatedTable gen 生成表；不能为 null
     */
    public JdbcTableConfig(String name, @Nullable Class<?> cls, Table<?> generatedTable) {
        this(name, cls, generatedTable.getName(), generatedTable);
    }

    /**
     * 使用生成表的 rename 操作应用物理表名，保留全部字段类型。
     * @param name 表配置名称；不能为 null
     * @param cls 领域类型；可为 null
     * @param table 物理表名，可含 schema；不能为 null
     * @param generatedTable gen 生成表；不能为 null
     * @throws IllegalArgumentException 未提供生成表时抛出
     */
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

    /**
     * 读取单张表的可选物理名称，未配置时使用模块提供的生成表名称。
     * @param name 表配置名称；不能为 null
     * @param cls 所属领域类型；可为 null
     * @param generatedTable 所属模块的生成表；不能为 null
     * @param properties 宿主配置；不能为 null
     * @return 绑定生成表类型的配置，不创建或查询数据库
     */
    public static JdbcTableConfig configured(String name, @Nullable Class<?> cls,
        Table<?> generatedTable, PropertyResolver properties) {
        String table = properties.getProperty("flow.jdbc.tables." + name + ".table", String.class)
            .orElse(generatedTable.getName());
        return new JdbcTableConfig(name, cls, table, generatedTable);
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
