package org.flow.core.jdbc;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.env.Environment;
import jakarta.inject.Named;
import org.flow.jdbc.JdbcTableConfig;
import org.flow.gen.flow.Tables;
import org.flow.core.models.executions.ExecutionOutput;
import org.flow.core.models.flows.FlowInterface;
import org.flow.core.models.topologies.FlowTopology;
import org.flow.core.lock.Lock;
import org.flow.core.models.namespaces.files.NamespaceFileMetadata;
import org.flow.core.models.kv.PersistedKvMetadata;
import org.flow.core.server.ServiceInstance;
import org.flow.core.models.Setting;
import org.flow.core.models.executions.TaskOutput;

/** 注册本模块拥有的生成表，并应用宿主配置的物理表名。 */
@Factory
@org.flow.core.jdbc.runner.JdbcRepositoryEnabled
public class CoreJdbcTableConfigsFactory {

    /**
     * 根据宿主配置创建本模块的表绑定，字段类型始终来自 gen。
     *
     * @param environment 宿主环境，用于读取可选的物理表名；不能为 null
     * @return 持有生成表类型的配置，不创建数据库表
     */
    @Bean
    @Named("flows")
    public JdbcTableConfig flows(Environment environment) {
        return JdbcTableConfig.configured("flows", FlowInterface.class, Tables.FLOWS, environment);
    }

    /**
     * 根据宿主配置创建本模块的表绑定，字段类型始终来自 gen。
     *
     * @param environment 宿主环境，用于读取可选的物理表名；不能为 null
     * @return 持有生成表类型的配置，不创建数据库表
     */
    @Bean
    @Named("settings")
    public JdbcTableConfig settings(Environment environment) {
        return JdbcTableConfig.configured("settings", Setting.class, Tables.SETTINGS, environment);
    }

    /**
     * 根据宿主配置创建本模块的表绑定，字段类型始终来自 gen。
     *
     * @param environment 宿主环境，用于读取可选的物理表名；不能为 null
     * @return 持有生成表类型的配置，不创建数据库表
     */
    @Bean
    @Named("flowtopologies")
    public JdbcTableConfig flowTopologies(Environment environment) {
        return JdbcTableConfig.configured("flowtopologies", FlowTopology.class, Tables.FLOW_TOPOLOGIES, environment);
    }

    /**
     * 根据宿主配置创建本模块的表绑定，字段类型始终来自 gen。
     *
     * @param environment 宿主环境，用于读取可选的物理表名；不能为 null
     * @return 持有生成表类型的配置，不创建数据库表
     */
    @Bean
    @Named("serviceinstance")
    public JdbcTableConfig serviceInstance(Environment environment) {
        return JdbcTableConfig.configured("serviceinstance", ServiceInstance.class, Tables.SERVICE_INSTANCE, environment);
    }

    /**
     * 根据宿主配置创建本模块的表绑定，字段类型始终来自 gen。
     *
     * @param environment 宿主环境，用于读取可选的物理表名；不能为 null
     * @return 持有生成表类型的配置，不创建数据库表
     */
    @Bean
    @Named("kvmetadata")
    public JdbcTableConfig kvMetadata(Environment environment) {
        return JdbcTableConfig.configured("kvmetadata", PersistedKvMetadata.class, Tables.KV_METADATA, environment);
    }

    /**
     * 根据宿主配置创建本模块的表绑定，字段类型始终来自 gen。
     *
     * @param environment 宿主环境，用于读取可选的物理表名；不能为 null
     * @return 持有生成表类型的配置，不创建数据库表
     */
    @Bean
    @Named("namespacefilemetadata")
    public JdbcTableConfig namespaceFileMetadata(Environment environment) {
        return JdbcTableConfig.configured("namespacefilemetadata", NamespaceFileMetadata.class, Tables.NAMESPACE_FILE_METADATA, environment);
    }

    /**
     * 根据宿主配置创建本模块的表绑定，字段类型始终来自 gen。
     *
     * @param environment 宿主环境，用于读取可选的物理表名；不能为 null
     * @return 持有生成表类型的配置，不创建数据库表
     */
    @Bean
    @Named("locks")
    public JdbcTableConfig locks(Environment environment) {
        return JdbcTableConfig.configured("locks", Lock.class, Tables.LOCKS, environment);
    }

    /**
     * 根据宿主配置创建本模块的表绑定，字段类型始终来自 gen。
     *
     * @param environment 宿主环境，用于读取可选的物理表名；不能为 null
     * @return 持有生成表类型的配置，不创建数据库表
     */
    @Bean
    @Named("taskoutputs")
    public JdbcTableConfig outputs(Environment environment) {
        return JdbcTableConfig.configured("taskoutputs", TaskOutput.class, Tables.TASK_OUTPUTS, environment);
    }

    /**
     * 根据宿主配置创建本模块的表绑定，字段类型始终来自 gen。
     *
     * @param environment 宿主环境，用于读取可选的物理表名；不能为 null
     * @return 持有生成表类型的配置，不创建数据库表
     */
    @Bean
    @Named("executionoutputs")
    public JdbcTableConfig executionOutputs(Environment environment) {
        return JdbcTableConfig.configured("executionoutputs", ExecutionOutput.class, Tables.EXECUTION_OUTPUTS, environment);
    }
}
