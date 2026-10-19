package org.flow.scheduler.jdbc.migration;

import java.io.IOException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;

import org.jooq.Cursor;
import org.jooq.JSONB;
import org.jooq.Record2;
import org.jooq.impl.DSL;

import static org.flow.gen.flow.Tables.TRIGGERS;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.flow.core.migration.MigrationScript;
import org.flow.core.models.flows.State;
import org.flow.core.models.triggers.Backfill;
import org.flow.core.models.triggers.TriggerId;
import org.flow.core.scheduler.SchedulerConfiguration;
import org.flow.core.scheduler.model.TriggerState;
import org.flow.core.scheduler.vnodes.VNodes;
import org.flow.jdbc.JdbcJsonbUtils;
import org.flow.core.serializers.JacksonMapper;
import org.flow.jdbc.JooqDSLContextWrapper;
import org.flow.core.jdbc.runner.JdbcRepositoryEnabled;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Migrates V1 trigger rows (stored as legacy {@code Trigger} JSON) to V2 {@link TriggerState} format.
 *
 * <p>
 * Reads all rows from the {@code triggers} table, deserializes the V1 JSON, converts each
 * to a {@link TriggerState}, and updates the row in-place. Generated columns (vnode, locked,
 * next_evaluation_epoch, etc.) are recomputed automatically by the database.
 */
@Slf4j
@Singleton
@JdbcRepositoryEnabled
public class V2_0_03TriggerMigration implements MigrationScript {

    private final JooqDSLContextWrapper dslContextWrapper;
    private final int vnodes;

    @Inject
    public V2_0_03TriggerMigration(
        final JooqDSLContextWrapper dslContextWrapper,
        final SchedulerConfiguration schedulerConfiguration) {
        this.dslContextWrapper = dslContextWrapper;
        this.vnodes = schedulerConfiguration.vnodes();
    }

    @Override
    public String scriptId() {
        return "2.0.03-triggers";
    }

    @Override
    public String description() {
        return "Migrate V1 trigger rows to TriggerState";
    }

    @Override
    public String checksum() {
        return null;
    }

    /**
     * 只读取生成触发器表的 key 和 value 列，将旧版 JSON 转为 TriggerState 后原位写回。
     *
     * @throws Exception 当迁移读取或写入失败时抛出
     */
    @Override
    public void migrate() throws Exception {
        dslContextWrapper.transaction(configuration ->
        {
            int migrated = 0;
            int skipped = 0;

            try (
                Cursor<Record2<String, JSONB>> cursor = DSL.using(configuration)
                    .select(TRIGGERS.KEY, TRIGGERS.VALUE)
                    .from(TRIGGERS)
                    .fetchLazy()
            ) {

                while (cursor.hasNext()) {
                    Record2<String, JSONB> row = cursor.fetchNext();
                    String key = row.get(TRIGGERS.KEY);
                    String json = row.get(TRIGGERS.VALUE).data();

                    try {
                        V1Trigger v1 = JacksonMapper.ofJson().readValue(json, V1Trigger.class);

                        if (v1.evaluatedAt() != null) {
                            skipped++;
                            continue;
                        }

                        TriggerState state = toTriggerState(v1);
                        String newJson = JacksonMapper.ofJson().writeValueAsString(state);

                        DSL.using(configuration)
                            .update(TRIGGERS)
                            .set(TRIGGERS.VALUE, JdbcJsonbUtils.valueOf(newJson))
                            .where(TRIGGERS.KEY.eq(key))
                            .execute();

                        migrated++;
                    } catch (IOException e) {
                        log.error("Failed to migrate trigger with key '{}'", key, e);
                        throw new RuntimeException(e);
                    }
                }
            }

            log.info("Trigger migration complete: {} row(s) migrated, {} already in V2 format.", migrated, skipped);
        });
    }

    private TriggerState toTriggerState(V1Trigger v1) {
        return TriggerState.builder()
            .tenantId(v1.tenantId())
            .namespace(v1.namespace())
            .flowId(v1.flowId())
            .triggerId(v1.triggerId())
            .updatedAt(v1.updatedDate())
            .evaluatedAt(v1.date() != null ? v1.date().toInstant() : null)
            .nextEvaluationDate(v1.nextExecutionDate() != null ? v1.nextExecutionDate().toInstant() : null)
            .backfill(v1.backfill())
            .stopAfter(v1.stopAfter())
            .disabled(v1.disabled() != null ? v1.disabled() : false)
            .workerId(v1.workerId())
            .vnode(
                VNodes.computeVNodeFromTrigger(
                    TriggerId.of(v1.tenantId(), v1.namespace(), v1.flowId(), v1.triggerId()),
                    vnodes
                )
            )
            .locked(v1.executionId() != null)
            .build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record V1Trigger(
        String tenantId,
        String namespace,
        String flowId,
        String triggerId,
        ZonedDateTime date,
        ZonedDateTime nextExecutionDate,
        String executionId,
        Instant updatedDate,
        String workerId,
        Backfill backfill,
        List<State.Type> stopAfter,
        Boolean disabled,
        Instant evaluatedAt) {
    }
}
