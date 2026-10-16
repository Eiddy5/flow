package org.flow.jdbc.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jooq.Field;
import org.jooq.impl.DSL;

import org.flow.gen.flow.Tables;
import org.flow.gen.flow.tables.LocksTable;
import org.flow.core.lock.Lock;
import org.flow.core.repositories.LockRepositoryInterface;
import org.flow.core.utils.IdUtils;

public class AbstractJdbcLockRepository extends AbstractJdbcRepository implements LockRepositoryInterface {
    private org.flow.jdbc.AbstractJdbcRepository<Lock> jdbcRepository;

    public AbstractJdbcLockRepository(org.flow.jdbc.AbstractJdbcRepository<Lock> jdbcRepository) {
        this.jdbcRepository = jdbcRepository;
    }

    @Override
    protected LocksTable table() {
        return jdbcRepository.getTable(Tables.LOCKS);
    }

    @Override
    public Optional<Lock> findById(String category, String id) {
        return jdbcRepository.getDslContextWrapper()
            .transactionResult(configuration ->
            {
                var select = DSL
                    .using(configuration)
                    .select(table().VALUE)
                    .from(table())
                    .where(table().KEY.eq(IdUtils.fromParts(category, id)));
                return this.jdbcRepository.fetchOne(select);
            });
    }

    @Override
    public boolean create(Lock newLock) {
        Map<Field<?>, Object> finalFields = this.jdbcRepository.persistFields(newLock);
        return jdbcRepository.getDslContextWrapper()
            .transactionResult(configuration -> DSL.using(configuration)
                .insertInto(table())
                .set(table().KEY, newLock.uid())
                .set(finalFields)
                .onDuplicateKeyIgnore()
                .execute() > 0);
    }

    @Override
    public void deleteById(String category, String id) {
        this.jdbcRepository.getDslContextWrapper()
            .transaction(
                configuration -> DSL.using(configuration)
                    .delete(table())
                    .where(table().KEY.eq(IdUtils.fromParts(category, id)))
                    .execute()
            );
    }

    @Override
    public List<Lock> deleteByOwner(String owner) {
        return this.jdbcRepository.getDslContextWrapper()
            .transactionResult(configuration ->
            {
                var dslContext = DSL.using(configuration);
                var select = dslContext
                    .select(table().VALUE)
                    .from(table())
                    .where(table().OWNER.eq(owner));
                var locks = this.jdbcRepository.fetch(select);
                dslContext
                    .delete(table())
                    .where(table().OWNER.eq(owner))
                    .execute();
                return locks;
            }
            );
    }
}
