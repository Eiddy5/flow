package org.cses.flow.infrastructure.datapilot;

import jakarta.inject.Singleton;
import org.dataPilot.datasource.DataSourceKeyProvider;

@Singleton
public final class DataSourceKey implements DataSourceKeyProvider {

    public static final String POSTGRESQL = "postgresql.flow";
    public static final String DEFAULT = POSTGRESQL;

    @Override
    public String getPostgreSQL() {
        return POSTGRESQL;
    }

    @Override
    public String getMariaDB() {
        return null;
    }

    @Override
    public String getMongoDB() {
        return null;
    }

    @Override
    public String getSystemAPI() {
        return null;
    }

    @Override
    public String getDefault() {
        return DEFAULT;
    }
}
