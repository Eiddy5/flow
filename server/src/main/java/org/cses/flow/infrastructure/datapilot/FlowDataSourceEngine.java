package org.cses.flow.infrastructure.datapilot;

import jakarta.inject.Singleton;
import org.dataPilot.EngineConfiguration;
import org.dataPilot.conf.DataSourceType;
import org.dataPilot.conf.DbType;
import org.dataPilot.conf.option.DbDataSourceOption;
import org.dataPilot.micronaut.MicronautDataSourceEngine;
import org.jooq.impl.SchemaImpl;
import org.paas.session.Session;
import org.paas.session.User;

@Singleton
public final class FlowDataSourceEngine
    extends MicronautDataSourceEngine<Session<User>, User> {

    public static final String ENGINE_KEY = "flow";

    @Override
    public String getKey() {
        return ENGINE_KEY;
    }

    @Override
    public void registerDataSources() {
        defineDbDataSource(postgreSqlOption());
    }

    DbDataSourceOption postgreSqlOption() {
        DbDataSourceOption option = new DbDataSourceOption();
        option.dbName = "flow";
        option.name = "default";
        option.title = "Flow PostgreSQL";
        option.jooqSchema = new SchemaImpl("public");
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
