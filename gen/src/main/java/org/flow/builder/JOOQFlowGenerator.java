package org.flow.builder;

import lombok.Getter;
import lombok.Setter;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.*;

@Setter
@Getter
public class JOOQFlowGenerator {

    /**
     * 从指定 PostgreSQL 基线生成应用与调度表代码。
     * @param args 当前不使用的命令行参数
     * @throws Exception 连接数据库或生成代码失败时抛出
     */
    public static void main(String[] args) throws Exception {
        Configuration configuration = new Configuration()
                .withGenerator(new Generator()
                        .withName("org.jooq.codegen.ext.generator.ExtJavaGenerator")
                        .withStrategy(new Strategy().withName("org.jooq.codegen.ext.generator.ExtJavaGeneratorStrategy"))
                        .withDatabase(new Database()
                                .withName("org.jooq.meta.postgres.PostgresDatabase")
                                .withInputSchema("public")
                                .withExcludes("databasechangelog|pgp_armor_headers|auth_permission_definition_backup_20260629_contacts")
                        )
                        .withTarget(new Target()
                                .withPackageName("org.flow.gen.flow")
                                .withDirectory("gen/src/main/java")
                        )
                        .withGenerate(new Generate()
                                .withPojos(true)
                        )
                );

        HikariConfig pool = new HikariConfig();
        pool.setDriverClassName("org.postgresql.Driver");
        pool.setJdbcUrl(environment("FLOW_JOOQ_JDBC_URL", "jdbc:postgresql://localhost:5432/flow"));
        pool.setUsername(environment("FLOW_JOOQ_JDBC_USER", "flow"));
        pool.setPassword(environment("FLOW_JOOQ_JDBC_PASSWORD", "flow"));
        pool.setMaximumPoolSize(1);
        pool.setMinimumIdle(0);
        pool.setPoolName("flow-codegen");
        try (HikariDataSource source = new HikariDataSource(pool)) {
            GenerationTool generator = new GenerationTool();
            generator.setDataSource(source);
            generator.run(configuration);
        }
    }

    /**
     * 读取生成器的独立环境配置，空值使用既有本地开发默认值。
     * @param name 非空环境变量名称
     * @param defaultValue 未提供配置时的开发默认值
     * @return 环境值或开发默认值，不输出配置内容
     */
    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
