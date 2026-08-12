package org.flow.builder;

import lombok.Getter;
import lombok.Setter;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.*;

@Setter
@Getter
public class JOOQFlowGenerator {

    public static void main(String[] args) throws Exception {
        Configuration configuration = new Configuration()
                .withJdbc(new Jdbc()
                        .withDriver("org.postgresql.Driver")
                        .withUrl(environment(
                                "FLOW_JOOQ_JDBC_URL",
                                "jdbc:postgresql://localhost:5432/flow"
                        ))
                        .withUser(environment("FLOW_JOOQ_JDBC_USER", "flow"))
                        .withPassword(environment("FLOW_JOOQ_JDBC_PASSWORD", "flow"))
                )
                .withGenerator(new Generator()
                        .withName("org.jooq.codegen.ext.generator.ExtJavaGenerator")
                        .withStrategy(new Strategy().withName("org.jooq.codegen.ext.generator.ExtJavaGeneratorStrategy"))
                        .withDatabase(new Database()
                                .withExcludes("databasechangelog")
                                .withName("org.jooq.meta.postgres.PostgresDatabase")
                                .withInputSchema("public")
                                .withExcludes("pgp_armor_headers|auth_permission_definition_backup_20260629_contacts")
                        )
                        .withTarget(new Target()
                                .withPackageName("org.flow.gen.flow")
                                .withDirectory("gen/src/main/java")
                        )
                        .withGenerate(new Generate()
                                .withPojos(true)
                        )
                );

        GenerationTool.generate(configuration);

    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
