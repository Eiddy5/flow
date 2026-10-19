package org.flow.core.jdbc.postgres;

import org.flow.core.events.CrudEvent;
import org.flow.core.models.Setting;
import org.flow.core.repositories.RepositoryBean;
import org.flow.core.jdbc.repository.AbstractJdbcSettingRepository;

import io.micronaut.context.event.ApplicationEventPublisher;
import jakarta.inject.Inject;
import jakarta.inject.Named;

@RepositoryBean
@PostgresRepositoryEnabled
public class PostgresSettingRepository extends AbstractJdbcSettingRepository {
    @Inject
    public PostgresSettingRepository(@Named("settings") PostgresRepository<Setting> repository,
        ApplicationEventPublisher<CrudEvent<Setting>> eventPublisher) {
        super(repository, eventPublisher);
    }
}
