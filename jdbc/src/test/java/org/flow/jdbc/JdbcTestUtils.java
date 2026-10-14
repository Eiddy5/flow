package org.flow.jdbc;

import java.util.List;

import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.impl.DSL;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import lombok.SneakyThrows;

@Context
@Requires(property = "flow.repository.type", value = "postgres")
public class JdbcTestUtils {
    @Inject
    protected JooqDSLContextWrapper dslContextWrapper;

    @Inject
    private JdbcTableConfigs tableConfigs;

    List<Table<?>> tables;

    @PostConstruct
    @SneakyThrows
    public void setup() {

        dslContextWrapper.transaction((configuration) ->
        {
            DSLContext dslContext = DSL.using(configuration);

            this.tables = dslContext.fetch("""
                    SELECT current_schema() AS schema_name, table_name
                    FROM information_schema.tables
                    WHERE table_schema = current_schema()
                    """)
                .stream()
                .filter(row -> tableConfigs.getTableConfigs().stream()
                    .anyMatch(conf -> conf.table().equalsIgnoreCase(row.get("table_name", String.class))))
                .<Table<?>>map(row -> DSL.table(DSL.name(
                    row.get("schema_name", String.class), row.get("table_name", String.class))))
                .toList();
        });
    }

    /**
     * This should never be used ideally in OSS as it defeats the concurrent test runs and may drop a table in the middle of another test
     */
    @Deprecated
    @SneakyThrows
    public void drop() {
        dslContextWrapper.transaction((configuration) ->
        {
            DSLContext dslContext = DSL.using(configuration);

            this.tables.forEach(t -> dslContext.delete(t).execute());
        });
    }

    /**
     * No-op. Migrations are now automatically applied at context startup via
     * {@code MigrationRunner.@PostConstruct}.
     */
    @Deprecated
    public void migrate() {
    }
}
