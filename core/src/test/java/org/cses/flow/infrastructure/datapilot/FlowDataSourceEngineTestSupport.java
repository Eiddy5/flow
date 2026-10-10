package org.cses.flow.infrastructure.datapilot;

import org.dataPilot.DataSourceEngine;
import org.dataPilot.EngineConfiguration;
import org.dataPilot.datasource.DatasourceManager;
import org.dataPilot.handler.CommonExecutor;
import org.dataPilot.handler.DataDeleter;
import org.dataPilot.handler.DataQuery;
import org.dataPilot.handler.DataUpdater;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.concurrent.atomic.AtomicInteger;

/** 初始化真实字段、元数据与 CRUD Handler，不启动外部索引、插件和消费者。 */
public class FlowDataSourceEngineTestSupport extends FlowDataSourceEngine implements AutoCloseable {
    private AtomicInteger queries = new AtomicInteger();
    private AtomicInteger updates = new AtomicInteger();
    private AtomicInteger deletes = new AtomicInteger();
    private AtomicInteger executions = new AtomicInteger();

    /** 初始化生产 Flow 配置及全部生成表；原生索引只装配客户端，CRUD 禁用 ES 且不启动索引。 */
    public FlowDataSourceEngineTestSupport() {
        configureFlow(this);
        try (var nativeFields = new NativeFields()) {
            fieldFactories.putAll(nativeFields.fieldFactories);
            classFieldFactoryMap.putAll(nativeFields.classFieldFactoryMap);
        }
        datasourceManager = new DatasourceManager(this);
        pulsarFactory = new IndexPulsarFactory();
        esHosts = java.util.List.of("http://127.0.0.1:1");
        index = new org.dataPilot.dbindex.DataPilotIndex(this);
        registerDataSources();
    }

    /** 已通过构造器完成专属测试初始化，不运行外部插件或消费者。 */
    @Override
    public void postConstruct() { }

    /**
     * 计数后返回生产查询 Builder，不替换数据库 Handler。
     * @param session 可空业务会话
     * @param key 已声明数据源键
     * @param collection 生成表对应集合名
     * @return 生产原生查询 Builder
     */
    @Override
    public DataQuery<Session<User>, User, ?> buildQuery(Session<User> session, String key, String collection) {
        queries.incrementAndGet();
        return super.buildQuery(session, key, collection);
    }

    /**
     * 计数后返回生产写入 Builder。
     * @param session 可空业务会话
     * @param key 已声明数据源键
     * @param collection 生成表对应集合名
     * @return 生产原生写入 Builder
     */
    @Override
    public DataUpdater<Session<User>, User, ?> buildUpdater(Session<User> session, String key, String collection) {
        updates.incrementAndGet();
        return super.buildUpdater(session, key, collection);
    }

    /**
     * 计数后返回生产删除 Builder。
     * @param session 可空业务会话
     * @param key 已声明数据源键
     * @param collection 生成表对应集合名
     * @return 生产原生删除 Builder
     */
    @Override
    public DataDeleter<Session<User>, User, ?> buildDeleter(Session<User> session, String key, String collection) {
        deletes.incrementAndGet();
        return super.buildDeleter(session, key, collection);
    }

    /**
     * 计数后返回生产事务执行器。
     * @param session 可空业务会话
     * @return 生产原生事务执行器
     */
    @Override
    public CommonExecutor<Session<User>, User> useExecutor(Session<User> session) {
        executions.incrementAndGet();
        return super.useExecutor(session);
    }

    /** @return 实际创建的查询 Builder 数量 */
    public int queries() { return queries.get(); }
    /** @return 实际创建的写入 Builder 数量 */
    public int updates() { return updates.get(); }
    /** @return 实际创建的删除 Builder 数量 */
    public int deletes() { return deletes.get(); }
    /** @return 实际创建的复杂 SQL 执行器数量 */
    public int executions() { return executions.get(); }

    /**
     * 关闭本测试拥有的索引客户端和异步执行器；外部 JDBC 连接仍由调用方管理。
     * @throws IllegalStateException 索引传输关闭失败时抛出，执行器仍关闭
     */
    @Override
    public void close() {
        try {
            if (index != null && index.client != null) index.client._transport().close();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot close test index transport", exception);
        } finally {
            asyncExecutor.shutdownNow();
        }
    }

    /** 提供第三方引擎内建字段注册，避免 Micronaut 注册额外扫描组件。 */
    private static class NativeFields extends DataSourceEngine<Session<User>, User> implements AutoCloseable {
        /** 仅注册真实内建字段工厂，不启动引擎。 */
        private NativeFields() { registerFieldTypes(); }
        /** @return 禁止 SDK 无参构造器隐式初始化、启动插件和消费者 */
        @Override
        public Boolean inFramework() { return true; }
        /**
         * 字段注册阶段无需数据库配置。
         * @param configuration 未修改的原生配置
         */
        @Override
        public void configuration(EngineConfiguration configuration) { }
        /** 字段注册阶段不增加数据源。 */
        @Override
        public void registerDataSources() { }
        /** 字段注册器不注册引擎插件。 */
        @Override
        public void registerPlugins() { }
        /** @return 字段注册阶段不创建或使用缓存管理器 */
        @Override
        public org.dataPilot.cache.CacheManager initCacheManager() { return null; }
        /** 关闭字段注册器创建的执行器。 */
        @Override
        public void close() { asyncExecutor.shutdownNow(); }
    }

    /** 未启动索引只需要原生惰性消息包装器，实际 UC 队列仍使用真实 Broker。 */
    private static class IndexPulsarFactory extends org.paas.pulsar.PulsarFactory {
        /**
         * 构造不连接 Broker 的原生索引包装器，不创建生产者或消费者。
         * @param topic 索引组件声明的主题名
         * @param type 索引消息类型
         * @param <T> 消息类型
         * @return 无客户端的惰性包装器；禁止通过此测试协作者发送或监听
         */
        @Override
        public <T> org.paas.pulsar.Pulsar<T> get(String topic, Class<T> type) {
            return new org.paas.pulsar.Pulsar<>(null,
                new IndexPulsarConfiguration(), topic, type);
        }
    }

    /** 为没有客户端的索引包装器提供惰性配置，不具备外部消息连接。 */
    private static class IndexPulsarConfiguration implements org.paas.pulsar.config.PulsarClientConfiguration {
        /** @return 未提供消息客户端地址 */
        @Override public String getServiceUrl() { return null; }
        /** @return 没有外部消息认证 */
        @Override public org.apache.pulsar.client.api.Authentication getAuthentication() { return null; }
        /** @return 未指定 SSL 提供者 */
        @Override public java.util.Optional<String> getSslProvider() { return java.util.Optional.empty(); }
        /** @return 未指定证书仓库 */
        @Override public java.util.Optional<String> getTlsTrustStorePath() { return java.util.Optional.empty(); }
        /** @return 未指定证书仓库凭据 */
        @Override public java.util.Optional<String> getTlsTrustStorePassword() { return java.util.Optional.empty(); }
        /** @return 未指定 TLS 证书 */
        @Override public java.util.Optional<String> getTlsCertFilePath() { return java.util.Optional.empty(); }
        /** @return 未指定 TLS 主机校验 */
        @Override public java.util.Optional<Boolean> getTlsVerifyHostname() { return java.util.Optional.empty(); }
        /** @return 未指定不安全 TLS 连接策略 */
        @Override public java.util.Optional<Boolean> getTlsAllowInsecureConnection() { return java.util.Optional.empty(); }
        /** @return 未指定 TLS 密码套件 */
        @Override public java.util.Optional<java.util.Set<String>> getTlsCiphers() { return java.util.Optional.empty(); }
        /** @return 未指定 TLS 协议 */
        @Override public java.util.Optional<java.util.Set<String>> getTlsProtocols() { return java.util.Optional.empty(); }
        /** @return 保留索引主题原名，不采用宿主开发主题前缀 */
        @Override public java.util.Optional<Boolean> getDevelopment() { return java.util.Optional.of(false); }
    }
}
