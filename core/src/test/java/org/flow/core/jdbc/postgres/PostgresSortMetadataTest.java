package org.flow.core.jdbc.postgres;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import org.flow.core.models.Label;
import org.flow.core.models.QueryFilter;
import org.flow.core.models.flows.Flow;
import org.flow.gen.flow.Tables;
import org.flow.jdbc.JdbcTableConfig;
import org.flow.jdbc.JooqDSLContextWrapper;

import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.Sort;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@MicronautTest(transactional = false)
class PostgresSortMetadataTest {
    @Inject
    JooqDSLContextWrapper transactions;

    /** 验证配置后的生成表支持持久化、标签、源码全文、排序及更新，并保持 schema 隔离。 */
    @Test
    void writesReadsAndSortsGeneratedShapeThroughConfiguredSchemaAndTableName() {
        String peerSchema = "gen_peer_" + UUID.randomUUID().toString().replace("-", "");
        var physicalName = DSL.name(peerSchema, "Flow Rows");
        var repository = new PostgresRepository<Flow>(new JdbcTableConfig("flows", Flow.class,
            DSL.using(org.jooq.SQLDialect.POSTGRES).render(physicalName), Tables.FLOWS), transactions);
        var table = repository.getTable(Tables.FLOWS);
        String namespace = "org.flow.gen." + peerSchema;

        try {
            transactions.transaction(configuration -> {
                var context = DSL.using(configuration);
                // This module migrates a dedicated test_<uuid> schema in the harness database.
                // Reject accidental execution against a non-isolated schema before creating fixtures.
                String originalSchema = context.fetchOne("select current_schema()").get(0, String.class);
                assertThat(originalSchema).startsWith("test_");
                context.execute("CREATE SCHEMA {0}", DSL.name(peerSchema));
                context.execute("CREATE TABLE {0} (LIKE {1} INCLUDING ALL)", physicalName, Tables.FLOWS);

                for (String id : List.of("z-last", "a-first")) {
                    Flow flow = Flow.builder().id(id).namespace(namespace).revision(1).tasks(List.of())
                        .labels(Label.from(Map.of("group", id))).build();
                    var values = repository.persistFields(flow);
                    values.put(table.SOURCE_CODE, "id: " + id + "\ndescription: sourceword");
                    repository.persist(flow, context, values);
                }

                assertThat(context.fetchCount(table,
                    PostgresFlowRepositoryService.findCondition(repository, null, Map.of("group", "a-first"))))
                    .isEqualTo(1);
                assertThat(context.fetchCount(table,
                    PostgresFlowRepositoryService.findSourceCodeCondition(repository, "sourceword")))
                    .isEqualTo(2);
                var alias = table.as("current_flow");
                assertThat(context.fetchCount(alias,
                    PostgresFlowRepositoryService.findCondition(alias.VALUE, Map.of("group", "a-first"), QueryFilter.Op.EQUALS)))
                    .isEqualTo(1);

                var select = context.select().from(table).where(DSL.trueCondition());
                var rows = repository.sort(select, Pageable.from(1, 10, Sort.of(Sort.Order.asc("ID")))).fetch();
                assertThat(rows.getValues(table.ID)).containsExactly("a-first", "z-last");
                assertThat(rows.map(repository::map)).extracting(Flow::getNamespace).containsOnly(namespace);
                assertThat(rows.getValues(table.REVISION)).containsOnly(1);

                Flow updated = repository.map(rows.getFirst()).toBuilder().description("updated custom table").build();
                var values = repository.persistFields(updated);
                values.put(table.SOURCE_CODE, "id: a-first");
                repository.persist(updated, context, values);
                var restored = repository.fetchOne(context.select().from(table)
                    .where(table.ID.eq("a-first"))).orElseThrow();
                assertThat(restored.getDescription()).isEqualTo("updated custom table");
                assertThat(context.fetchCount(Tables.FLOWS, Tables.FLOWS.NAMESPACE.eq(namespace))).isZero();
                assertThat(context.fetchValue("select current_schema()", String.class)).isEqualTo(originalSchema);
            });
        } finally {
            transactions.transaction(configuration ->
                DSL.using(configuration).execute("DROP SCHEMA IF EXISTS {0} CASCADE", DSL.name(peerSchema)));
        }
    }

    /** 验证排序只接收当前生成表的字段，同时保留配置后的 schema 与物理表名。 */
    @Test
    void sortsOnlyGeneratedFieldsOfTheConfiguredTable() {
        String peerSchema = "sort_peer_" + UUID.randomUUID().toString().replace("-", "");
        var local = new PostgresRepository<>(new JdbcTableConfig("localProbe", Object.class, "sort_probe", Tables.FLOWS), transactions);
        var peer = new PostgresRepository<>(new JdbcTableConfig("peerProbe", Object.class, peerSchema + ".sort_probe", Tables.EXECUTIONS), transactions);
        var localTable = local.getTable(Tables.FLOWS);
        var peerTable = peer.getTable(Tables.EXECUTIONS);
        transactions.transaction(configuration -> {
            var context = DSL.using(configuration);
            context.execute("CREATE TABLE {0} (id TEXT, foreign_only TEXT)", localTable);
            context.execute("CREATE SCHEMA {0}", DSL.name(peerSchema));
            context.execute("CREATE TABLE {0} (id TEXT, flow_id TEXT)", peerTable);
        });
        try {
            transactions.transaction(configuration -> {
                var context = DSL.using(configuration);
                assertThatThrownBy(() -> local.sort(
                    context.select().from(localTable).where(DSL.trueCondition()),
                    Pageable.from(1, 10, Sort.of(Sort.Order.asc("foreign_only")))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not exist");
                assertThatThrownBy(() -> local.sort(
                    context.select().from(localTable).where(DSL.trueCondition()),
                    Pageable.from(1, 10, Sort.of(Sort.Order.asc("flowId")))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not exist");
                assertThatCode(() -> local.sort(
                    context.select(localTable.ID).from(localTable).where(DSL.trueCondition()),
                    Pageable.from(1, 10, Sort.of(Sort.Order.asc("ID")))).fetch())
                    .doesNotThrowAnyException();
                assertThatCode(() -> peer.sort(
                    context.select(peerTable.ID).from(peerTable).where(DSL.trueCondition()),
                    Pageable.from(1, 10, Sort.of(Sort.Order.asc("flowId")))).fetch())
                    .doesNotThrowAnyException();
            });
        } finally {
            transactions.transaction(configuration -> {
                var context = DSL.using(configuration);
                context.execute("DROP TABLE {0}", localTable);
                context.execute("DROP SCHEMA {0} CASCADE", DSL.name(peerSchema));
            });
        }
    }
}
