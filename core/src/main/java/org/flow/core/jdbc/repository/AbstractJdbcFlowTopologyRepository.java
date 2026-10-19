package org.flow.core.jdbc.repository;

import java.util.ArrayList;
import java.util.List;

import org.jooq.*;
import org.jooq.impl.DSL;

import org.flow.gen.flow.Tables;
import org.flow.gen.flow.tables.FlowTopologiesTable;
import org.flow.core.models.flows.FlowInterface;
import org.flow.core.models.topologies.FlowTopology;
import org.flow.core.repositories.FlowTopologyRepositoryInterface;

public abstract class AbstractJdbcFlowTopologyRepository extends AbstractJdbcRepository implements FlowTopologyRepositoryInterface {
    protected org.flow.core.jdbc.AbstractJdbcRepository<FlowTopology> jdbcRepository;

    public AbstractJdbcFlowTopologyRepository(org.flow.core.jdbc.AbstractJdbcRepository<FlowTopology> jdbcRepository) {
        this.jdbcRepository = jdbcRepository;
    }

    @Override
    protected FlowTopologiesTable table() {
        return jdbcRepository.getTable(Tables.FLOW_TOPOLOGIES);
    }

    @Override
    public List<FlowTopology> findByFlow(String tenantId, String namespace, String flowId, Boolean destinationOnly) {
        return jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                List<Condition> ors = new ArrayList<>();
                ors.add(
                    DSL.and(
                        eqOrIsNull(table().DESTINATION_TENANT_ID, tenantId),
                        table().DESTINATION_NAMESPACE.eq(namespace),
                        table().DESTINATION_ID.eq(flowId)
                    )
                );

                if (!destinationOnly) {
                    ors.add(
                        DSL.and(
                            eqOrIsNull(table().SOURCE_TENANT_ID, tenantId),
                            table().SOURCE_NAMESPACE.eq(namespace),
                            table().SOURCE_ID.eq(flowId)
                        )
                    );
                }

                Select<Record1<JSONB>> from = DSL
                    .using(configuration)
                    .select(table().VALUE)
                    .from(table())
                    .where(DSL.or(ors));

                return this.jdbcRepository.fetch(from);
            });
    }

    @Override
    public List<FlowTopology> findByNamespace(String tenantId, String namespace) {
        return jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                List<Condition> ors = new ArrayList<>();
                ors.add(
                    DSL.and(
                        eqOrIsNull(table().DESTINATION_TENANT_ID, tenantId),
                        table().DESTINATION_NAMESPACE.eq(namespace),
                        eqOrIsNull(table().SOURCE_TENANT_ID, tenantId),
                        table().SOURCE_NAMESPACE.eq(namespace)
                    )
                );

                Select<Record1<JSONB>> from = DSL
                    .using(configuration)
                    .select(table().VALUE)
                    .from(table())
                    .where(DSL.or(ors));

                return this.jdbcRepository.fetch(from);
            });
    }

    @Override
    public List<FlowTopology> findByNamespacePrefix(String tenantId, String namespacePrefix) {
        return jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                // Match flows that originate from the namespace or its children
                Condition sourceCondition = table().SOURCE_NAMESPACE.eq(namespacePrefix)
                    .or(table().SOURCE_NAMESPACE.startsWith(namespacePrefix + "."));

                Condition tenantSource = eqOrIsNull(table().SOURCE_TENANT_ID, tenantId);
                Condition tenantDest = eqOrIsNull(table().DESTINATION_TENANT_ID, tenantId);

                Select<Record1<JSONB>> from = DSL
                    .using(configuration)
                    .select(table().VALUE)
                    .from(table())
                    .where(tenantSource.and(tenantDest).and(sourceCondition));

                return this.jdbcRepository.fetch(from);
            });
    }

    @Override
    public List<FlowTopology> findAll(String tenantId) {
        return jdbcRepository
            .getDslContextWrapper()
            .transactionResult(configuration ->
            {
                List<Condition> ors = new ArrayList<>();
                ors.add(
                    DSL.and(
                        eqOrIsNull(table().DESTINATION_TENANT_ID, tenantId),
                        eqOrIsNull(table().SOURCE_TENANT_ID, tenantId)
                    )
                );

                Select<Record1<JSONB>> from = DSL
                    .using(configuration)
                    .select(table().VALUE)
                    .from(table())
                    .where(DSL.or(ors));

                return this.jdbcRepository.fetch(from);
            });
    }

    @Override
    public void save(FlowInterface flow, List<FlowTopology> flowTopologies) {
        jdbcRepository
            .getDslContextWrapper()
            .transaction(configuration ->
            {
                DSLContext context = DSL.using(configuration);

                context
                    .delete(table())
                    .where(
                        DSL.or(
                            DSL.and(
                                eqOrIsNull(table().DESTINATION_TENANT_ID, flow.getTenantId()),
                                table().DESTINATION_NAMESPACE.eq(flow.getNamespace()),
                                table().DESTINATION_ID.eq(flow.getId())
                            ),
                            DSL.and(
                                eqOrIsNull(table().SOURCE_TENANT_ID, flow.getTenantId()),
                                table().SOURCE_NAMESPACE.eq(flow.getNamespace()),
                                table().SOURCE_ID.eq(flow.getId())
                            )
                        )
                    )
                    .execute();

                if (!flowTopologies.isEmpty()) {
                    context
                        .batch(
                            flowTopologies
                                .stream()
                                .map(flowTopology -> buildMergeStatement(context, flowTopology))
                                .toList()
                        )
                        .execute();
                }
            });
    }

    protected DMLQuery<?> buildMergeStatement(DSLContext context, FlowTopology flowTopology) {
        return context.mergeInto(table())
            .using(context.selectOne())
            .on(table().KEY.eq(this.jdbcRepository.key(flowTopology)))
            .whenMatchedThenUpdate()
            .set(this.jdbcRepository.persistFields(flowTopology))
            .whenNotMatchedThenInsert()
            .set(table().KEY, this.jdbcRepository.key(flowTopology))
            .set(this.jdbcRepository.persistFields(flowTopology));
    }

    @Override
    public FlowTopology save(FlowTopology flowTopology) {
        this.jdbcRepository.persist(flowTopology);

        return flowTopology;
    }

}
