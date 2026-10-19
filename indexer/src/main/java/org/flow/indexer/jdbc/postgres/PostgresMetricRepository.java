package org.flow.indexer.jdbc.postgres;

import org.flow.core.jdbc.postgres.PostgresRepository;
import org.flow.core.jdbc.postgres.PostgresRepositoryEnabled;
import org.flow.core.jdbc.postgres.PostgresRepositoryUtils;

import java.sql.Timestamp;

import org.jooq.Field;

import org.flow.core.models.executions.MetricEntry;
import org.flow.core.repositories.RepositoryBean;
import org.flow.indexer.jdbc.repository.AbstractJdbcMetricRepository;

import jakarta.inject.Inject;
import jakarta.inject.Named;

@RepositoryBean
@PostgresRepositoryEnabled
public class PostgresMetricRepository extends AbstractJdbcMetricRepository {
    @Inject
    public PostgresMetricRepository(@Named("metrics") PostgresRepository<MetricEntry> repository) {
        super(repository);
    }

    /**
     * 将查询表的生成时间字段投影到 UTC，保证分组结果不受连接时区影响。
     *
     * @param dateField 当前查询使用的时间字段
     * @return UTC 墙上时间表达式
     */
    @Override
    protected Field<Timestamp> groupByTimestampField(Field<?> dateField) {
        return PostgresRepositoryUtils.utcTimestampField(dateField);
    }

}
