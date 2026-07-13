package org.flow.builder;

import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.*;

public class JooqMMGenerator {
    public static void main(String[] args) throws Exception {
        Configuration configuration = new Configuration()
                .withJdbc(new Jdbc()
                        .withDriver("org.postgresql.Driver")
                        .withUrl("jdbc:postgresql://postgresql-cses-pre-cnpg-rw.postgres-cses.svc.cluster.local:5432/mattermost-pre")
                        .withUser("postgres")
                        .withPassword("one.2013")
                )
                .withGenerator(new Generator()
                        .withName("org.jooq.codegen.ext.generator.ExtJavaGenerator")
                        .withStrategy(new Strategy().withName("org.jooq.codegen.ext.generator.ExtJavaGeneratorStrategy"))
                        .withDatabase(new Database()
                                .withName("org.jooq.meta.postgres.PostgresDatabase")
                                .withInputSchema("public").withExcludes("tokens")
                        )
                        .withTarget(new Target()
                                .withPackageName("org.cses.gen.mattermost")
                                .withDirectory("gen/src/main/java")
                        )
                        .withGenerate(new Generate()
                                .withPojos(true)
                        )
                );

        GenerationTool.generate(configuration);

    }
}
