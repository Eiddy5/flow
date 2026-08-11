package org.cses.flow.infrastructure.datapilot;

import io.micronaut.context.annotation.Secondary;
import jakarta.inject.Singleton;
import org.dataPilot.EngineConfiguration;
import org.dataPilot.conf.DataSourceType;
import org.dataPilot.conf.DbType;
import org.dataPilot.conf.option.DbDataSourceOption;
import org.dataPilot.datasource.DataSourceKeyProvider;
import org.dataPilot.micronaut.MicronautDataSourceEngine;
import org.flow.gen.flow.Public;
import org.paas.session.Session;
import org.paas.session.User;
@Secondary
@Singleton
public final class FlowDataSourceEngine
    extends MicronautDataSourceEngine<Session<User>, User> {

    public static final String ENGINE_KEY = "flow";

    private final DataSourceKey flowDataSourceKey = new DataSourceKey();

    @Override
    public String getKey() {
        return ENGINE_KEY;
    }

    @Override
    public void registerDataSources() {
        defineDbDataSource(flowDatasource());
    }

    @Override
    public String actualDataSourceKey(String declaredKey) {
        return DataSourceKeyProvider.actualDataSourceKey(
            flowDataSourceKey,
            declaredKey
        );
    }

    DbDataSourceOption flowDatasource() {
        DbDataSourceOption option = new DbDataSourceOption();
        option.dbName = "flow";
        option.name = "default";
        option.title = "flow 数据源";
        option.jooqSchema = Public.PUBLIC;
        option.type = DataSourceType.orm;
        option.dbType = DbType.postgresql;
        return option;
    }

    @Override
    public void configuration(EngineConfiguration configuration) {
        super.configuration(configuration);
        configureFlow(configuration);
    }

    void configureFlow(EngineConfiguration configuration) {
        configuration.isDebug = false;
        configuration.openTrace(true);
        configuration.setOpenDetailTrace(false);
        configuration.setDefaultDBDataSource(DataSourceKey.POSTGRESQL);
        configuration.setManagerDataSourceKey(DataSourceKey.POSTGRESQL);
    }
}
