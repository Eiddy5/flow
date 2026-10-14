package org.flow.jdbc.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jooq.*;
import org.jooq.impl.DSL;

import org.flow.core.events.CrudEvent;
import org.flow.core.events.CrudEventType;
import org.flow.core.models.Setting;
import org.flow.core.repositories.SettingRepositoryInterface;

import io.micronaut.context.event.ApplicationEventPublisher;
import lombok.SneakyThrows;

public abstract class AbstractJdbcSettingRepository extends AbstractJdbcCrudRepository<Setting> implements SettingRepositoryInterface {
    private final ApplicationEventPublisher<CrudEvent<Setting>> eventPublisher;

    @SuppressWarnings("unchecked")
    public AbstractJdbcSettingRepository(
        org.flow.jdbc.AbstractJdbcRepository<Setting> jdbcRepository,
        ApplicationEventPublisher<CrudEvent<Setting>> eventPublisher) {
        super(jdbcRepository);
        this.eventPublisher = eventPublisher;
    }

    public Boolean isTaskRunEnabled() {
        return false;
    }

    @Override
    public Optional<Setting> findByKey(String key) {
        return findOne(DSL.noCondition(), KEY_FIELD.eq(key));
    }

    @Override
    public List<Setting> findAll() {
        return findAll(DSL.noCondition());
    }

    @Override
    public Setting save(Setting setting) {
        this.eventPublisher.publishEvent(new CrudEvent<>(setting, null, CrudEventType.UPDATE));

        return internalSave(setting);
    }

    @Override
    public Setting internalSave(Setting setting) {
        Map<Field<Object>, Object> fields = this.jdbcRepository.persistFields(setting);
        this.jdbcRepository.persist(setting, fields);

        return setting;
    }

    @SneakyThrows
    @Override
    public Setting delete(Setting setting) {
        Optional<Setting> get = this.findByKey(setting.getKey());
        if (get.isEmpty()) {
            throw new IllegalStateException("Setting " + setting.getKey() + " doesn't exists");
        }

        this.jdbcRepository.delete(setting);
        this.eventPublisher.publishEvent(CrudEvent.delete(setting));

        return setting;
    }

    @Override
    protected Condition defaultFilter(String tenantId) {
        return buildTenantCondition(tenantId);
    }

    @Override
    protected Condition defaultFilter() {
        return DSL.noCondition();
    }
}
