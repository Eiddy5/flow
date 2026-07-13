package org.flow.builder;

import lombok.Getter;
import lombok.Setter;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.*;

@Setter
@Getter
public class JooqCSESGenerator {

    public static void main(String[] args) throws Exception {
        Configuration configuration = new Configuration()
                .withJdbc(new Jdbc()
                        .withDriver("org.postgresql.Driver")
                        .withUrl("jdbc:postgresql://192.168.6.34:5432/cses")
                        .withUser("postgres")
                        .withPassword("postgres")
                )
//                .withJdbc(new Jdbc()
//                        .withDriver("org.postgresql.Driver")
//                        .withUrl("jdbc:postgresql://postgresql-cses-pre-cnpg-rw.postgres-cses.svc.cluster.local:5432/cses")
//                        .withUser("postgres")
//                        .withPassword("one.2013")
//                )
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
}
