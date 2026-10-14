package org.flow.repository.postgres;

import java.util.UUID;

import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import org.flow.jdbc.JdbcTableConfig;
import org.flow.jdbc.JooqDSLContextWrapper;

import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.Sort;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@MicronautTest(transactional = false)
class PostgresSortMetadataTest {
    @Inject
    JooqDSLContextWrapper transactions;

    @Test
    void resolvesOnlyTheTargetTableIncludingQualifiedAndCaseSensitiveNames() {
        String peerSchema = "sort_peer_" + UUID.randomUUID().toString().replace("-", "");
        transactions.transaction(configuration -> {
            var context = DSL.using(configuration);
            context.execute("CREATE TABLE sort_probe (id TEXT, \"MixedCase\" TEXT)");
            context.execute("CREATE SCHEMA " + peerSchema);
            context.execute("CREATE TABLE " + peerSchema + ".sort_probe (id TEXT, foreign_only TEXT)");
        });
        try {
            var local = new PostgresRepository<>(new JdbcTableConfig("localProbe", Object.class, "sort_probe"), transactions);
            var peer = new PostgresRepository<>(new JdbcTableConfig("peerProbe", Object.class, peerSchema + ".sort_probe"), transactions);
            transactions.transaction(configuration -> {
                var context = DSL.using(configuration);
                assertThatThrownBy(() -> local.sort(
                    context.select().from("sort_probe").where(DSL.trueCondition()),
                    Pageable.from(1, 10, Sort.of(Sort.Order.asc("foreign_only")))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not exist");
                assertThatCode(() -> local.sort(
                    context.select().from("sort_probe").where(DSL.trueCondition()),
                    Pageable.from(1, 10, Sort.of(Sort.Order.asc("MIXEDCASE")))).fetch())
                    .doesNotThrowAnyException();
                assertThatCode(() -> peer.sort(
                    context.select().from(peerSchema + ".sort_probe").where(DSL.trueCondition()),
                    Pageable.from(1, 10, Sort.of(Sort.Order.asc("foreign_only")))).fetch())
                    .doesNotThrowAnyException();
            });
        } finally {
            transactions.transaction(configuration -> {
                var context = DSL.using(configuration);
                context.execute("DROP TABLE sort_probe");
                context.execute("DROP SCHEMA " + peerSchema + " CASCADE");
            });
        }
    }
}
