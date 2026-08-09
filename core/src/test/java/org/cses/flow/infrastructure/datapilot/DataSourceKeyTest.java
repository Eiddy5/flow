package org.cses.flow.infrastructure.datapilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.dataPilot.datasource.DataSourceKeyProvider;
import org.junit.jupiter.api.Test;

final class DataSourceKeyTest {

    private final DataSourceKey dataSourceKey = new DataSourceKey();

    @Test
    void mapsDefaultAndPostgreSqlToFlowDataSource() {
        assertEquals(
            DataSourceKey.POSTGRESQL,
            DataSourceKeyProvider.actualDataSourceKey(
                dataSourceKey,
                DataSourceKeyProvider.postgresql
            )
        );
        assertEquals(
            DataSourceKey.DEFAULT,
            DataSourceKeyProvider.actualDataSourceKey(
                dataSourceKey,
                DataSourceKeyProvider.Default
            )
        );
    }

    @Test
    void doesNotDeclareUnregisteredDataSources() {
        assertNull(dataSourceKey.getMariaDB());
        assertNull(dataSourceKey.getMongoDB());
        assertNull(dataSourceKey.getSystemAPI());
    }
}
