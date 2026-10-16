package org.flow.jdbc.repository;

import org.flow.core.models.flows.FlowInterface;
import org.flow.gen.flow.Tables;
import org.flow.jdbc.AbstractJdbcRepository;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;

public abstract class JdbcFlowRepositoryService {
    public static Table<Record> lastRevision(AbstractJdbcRepository<? extends FlowInterface> repository) {
        return lastRevision(repository, false);
    }

    /** 在计算最新版本前排除草稿，只有草稿的流程不会返回。 */
    public static Table<Record> lastNonDraftRevision(AbstractJdbcRepository<? extends FlowInterface> repository) {
        return lastRevision(repository, true);
    }

    /** 将最新版本键关联回生成表，外层查询始终使用同一组生成字段。 */
    private static Table<Record> lastRevision(AbstractJdbcRepository<? extends FlowInterface> repository, boolean excludeDraft) {
        var flows = repository.getTable(Tables.FLOWS);
        Field<Integer> rowNumber = DSL.rowNumber().over()
            .partitionBy(flows.TENANT_ID, flows.NAMESPACE, flows.ID)
            .orderBy(flows.REVISION.desc())
            .as("revision_rows");
        var ranked = DSL.select(flows.KEY, flows.REVISION, rowNumber)
            .from(flows)
            .where(excludeDraft ? flows.DRAFT.isFalse() : DSL.noCondition())
            .asTable("ranked_flows");
        var latest = DSL.select(ranked.field(flows.KEY), ranked.field(flows.REVISION))
            .from(ranked)
            .where(ranked.field(rowNumber).eq(1))
            .asTable("latest_flows");
        return flows.join(latest)
            .on(flows.KEY.eq(latest.field(flows.KEY)))
            .and(flows.REVISION.eq(latest.field(flows.REVISION)));
    }
}
