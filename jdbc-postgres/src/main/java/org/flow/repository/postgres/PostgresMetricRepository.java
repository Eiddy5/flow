package org.flow.repository.postgres;

import java.sql.Timestamp;

import org.jooq.Field;

import org.flow.core.models.executions.MetricEntry;
import org.flow.core.repositories.RepositoryBean;
import org.flow.jdbc.repository.AbstractJdbcMetricRepository;

import jakarta.inject.Inject;
import jakarta.inject.Named;

@RepositoryBean
@PostgresRepositoryEnabled
public class PostgresMetricRepository extends AbstractJdbcMetricRepository {
    @Inject
    public PostgresMetricRepository(@Named("metrics") PostgresRepository<MetricEntry> repository) {
        super(repository);
    }

    @Override
    protected Field<Timestamp> groupByTimestampField(String dateField) {
        return PostgresRepositoryUtils.utcTimestampField(dateField);
    }

}
