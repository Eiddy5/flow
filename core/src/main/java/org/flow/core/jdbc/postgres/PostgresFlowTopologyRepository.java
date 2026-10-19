package org.flow.core.jdbc.postgres;

import static org.flow.gen.flow.Tables.FLOW_TOPOLOGIES;

import org.jooq.DMLQuery;
import org.jooq.DSLContext;

import org.flow.core.models.topologies.FlowTopology;
import org.flow.core.repositories.RepositoryBean;
import org.flow.core.jdbc.repository.AbstractJdbcFlowTopologyRepository;

import jakarta.inject.Inject;
import jakarta.inject.Named;

@RepositoryBean
@PostgresRepositoryEnabled
public class PostgresFlowTopologyRepository extends AbstractJdbcFlowTopologyRepository {
    @Inject
    public PostgresFlowTopologyRepository(@Named("flowtopologies") PostgresRepository<FlowTopology> repository) {
        super(repository);
    }

    /**
     * 使用当前生成表及其主键字段保存流程拓扑，保持已有冲突更新边界。
     *
     * @param context 当前事务的 jOOQ 上下文
     * @param flowTopology 待保存的拓扑，不修改该对象
     * @return 尚未执行的插入或更新语句
     */
    @Override
    protected DMLQuery<?> buildMergeStatement(DSLContext context, FlowTopology flowTopology) {
        var table = this.jdbcRepository.getTable(FLOW_TOPOLOGIES);
        return context.insertInto(table)
            .set(table.KEY, this.jdbcRepository.key(flowTopology))
            .set(this.jdbcRepository.persistFields(flowTopology))
            .onConflict(table.KEY)
            .doUpdate()
            .set(this.jdbcRepository.persistFields(flowTopology));
    }
}
