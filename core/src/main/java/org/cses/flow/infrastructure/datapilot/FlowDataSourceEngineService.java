package org.cses.flow.infrastructure.datapilot;

import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.dataPilot.DataSourceEngine;
import org.dataPilot.manager.service.DataSourceService;
import org.paas.session.Session;
import org.paas.session.User;

@Singleton
@Named(FlowDataSourceEngine.ENGINE_KEY)
public final class FlowDataSourceEngineService
    extends DataSourceService<Session<User>, User> {

    private final FlowDataSourceEngine dataSourceEngine;

    public FlowDataSourceEngineService(
        FlowDataSourceEngine dataSourceEngine
    ) {
        this.dataSourceEngine = dataSourceEngine;
    }

    @Override
    public DataSourceEngine<Session<User>, User> createDataSourceEngine() {
        return dataSourceEngine;
    }
}
