package org.flow.gen.codegen;

import org.jooq.Schema;
import org.jooq.codegen.JavaWriter;
import org.jooq.codegen.ext.generator.ExtJavaGenerator;
import org.jooq.impl.DSL;
import org.jooq.meta.TableDefinition;

/**
 * 保留内部生成对象，并让生成表根据运行时限定名称解析 schema。
 */
public class FlowJavaGenerator extends ExtJavaGenerator {
    /**
     * 生成单张表，暂时关闭上游固定 schema 方法，并在完成后恢复生成配置。
     *
     * @param table 输出到默认 schema 的表定义；不能为 null
     * @param out 当前表的 Java 源码输出器；不能为 null
     * @throws IllegalArgumentException 当表未配置输出到默认 schema 时抛出
     */
    @Override
    protected void generateTable(TableDefinition table, JavaWriter out) {
        if (!table.getSchema().isDefaultSchema()) {
            throw new IllegalArgumentException("Flow table generation requires the default output schema.");
        }
        boolean defaultSchema = generateDefaultSchema();
        setGenerateDefaultSchema(false);
        try {
            super.generateTable(table, out);
        } finally {
            setGenerateDefaultSchema(defaultSchema);
        }
    }

    /**
     * 为生成表补充 schema 访问方法；别名不附带 schema，限定名称使用其前缀。
     *
     * @param table 当前生成的表定义；不能为 null
     * @param out 当前表的 Java 源码输出器；不能为 null
     */
    @Override
    protected void generateTableClassFooter(TableDefinition table, JavaWriter out) {
        super.generateTableClassFooter(table, out);
        String defaultSchema = out.ref(getStrategy().getFullJavaIdentifier(table.getSchema()), 2);
        out.overrideInherit();
        out.println("public %s getSchema() {", Schema.class);
        out.println("if (aliased()) {");
        out.println("return null;");
        out.println("}");
        out.println("return getQualifiedName().qualified()");
        out.println("? %s.schema(getQualifiedName().qualifier())", DSL.class);
        out.println(": %s;", defaultSchema);
        out.println("}");
    }
}
