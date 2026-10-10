package org.cses.flow.infrastructure.datapilot;

import io.micronaut.context.annotation.Secondary;
import jakarta.inject.Singleton;
import org.cses.flow.infrastructure.jooq.FlowDatabase;
import org.dataPilot.EngineConfiguration;
import org.dataPilot.common.EngineContext;
import org.dataPilot.common.ListResult;
import org.dataPilot.data.Model;
import org.dataPilot.conf.DataSourceType;
import org.dataPilot.conf.DbType;
import org.dataPilot.conf.AggregateType;
import org.dataPilot.conf.option.DbDataSourceOption;
import org.dataPilot.datasource.DataSourceKeyProvider;
import org.dataPilot.db.collection.DbCollectionOption;
import org.dataPilot.db.collection.DbCollection;
import org.dataPilot.db.conf.AggregateOptions;
import org.dataPilot.db.datasource.DbDataSource;
import org.dataPilot.db.transaction.JooqDbSessionImpl;
import org.dataPilot.db.handler.CacheQueryHandlerProxy;
import org.dataPilot.db.handler.DatabaseHandler;
import org.dataPilot.db.handler.JooqDatabaseHandler;
import org.dataPilot.db.handler.QueryHandlerProxyTemplate;
import org.dataPilot.handler.CommonExecutor;
import org.dataPilot.handler.DataDeleter;
import org.dataPilot.handler.DataQuery;
import org.dataPilot.handler.DataUpdater;
import org.dataPilot.micronaut.MicronautDataSourceEngine;
import org.dataPilot.micronaut.resolver.MicronautFieldOptionResolver;
import org.dataPilot.repository.request.FindRequest;
import org.dataPilot.repository.request.UpdateRequest;
import org.flow.gen.flow.Public;
import org.jooq.DSLContext;
import org.jooq.impl.TableImpl;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Flow 的原生 DataPilot CRUD 入口，注册生成元数据并适配调用方拥有的数据库上下文。 */
@Secondary
@Singleton
public class FlowDataSourceEngine
    extends MicronautDataSourceEngine<Session<User>, User> {

    public static final String ENGINE_KEY = "flow";

    private DataSourceKey flowDataSourceKey = new DataSourceKey();

    /** @return Flow 固定引擎键，不使用宿主默认引擎 */
    @Override
    public String getKey() {
        return ENGINE_KEY;
    }

    /** 注册 flow 数据源及生成元数据中的全部表；不执行 DDL 或读取数据库结构。 */
    @Override
    public void registerDataSources() {
        DbDataSource source = defineDbDataSource(flowDatasource());
        MicronautFieldOptionResolver resolver = new MicronautFieldOptionResolver(this);
        Public.PUBLIC.getTables().forEach(table -> {
            var builder = DbCollectionOption.builder().name(table.getName()).camelCase(false)
                .primaryKeys(table.getPrimaryKey().getFields().stream().map(org.jooq.Field::getName).toList());
            builder.fields(Arrays.stream(table.fields()).map(resolver::createFieldOption).toList());
            disableCollectionCache(source.addCollection(builder.build()));
        });
    }

    /**
     * Flow 快照只读取数据库，移除会在关闭缓存时仍读取单列主键的缓存代理。
     * @param <T> 原生集合 Model 类型
     * @param collection 当前生成元数据注册的集合，原生数据库 Handler 保持不变
     */
    @SuppressWarnings("unchecked")
    private <T extends Model> void disableCollectionCache(DbCollection<T> collection) {
        if (collection.metaModel.handler instanceof CacheQueryHandlerProxy<?> cache) {
            collection.metaModel.handler = (DatabaseHandler<T>) cache.handler;
        }
    }

    /**
     * 按 Flow 自有映射解析逻辑数据源键。
     * @param declaredKey 声明的数据源键，按 DataPilot 约定解析默认值与别名
     * @return 实际数据源键；不受宿主 DataSourceKeyProvider 影响
     */
    @Override
    public String actualDataSourceKey(String declaredKey) {
        return DataSourceKeyProvider.actualDataSourceKey(
            flowDataSourceKey,
            declaredKey
        );
    }

    /** @return 新的 Flow ORM 选项，具名连接与物理库均为 flow，Schema 元数据为 public */
    DbDataSourceOption flowDatasource() {
        DbDataSourceOption option = new DbDataSourceOption();
        option.dbName = FlowDatabase.DATA_SOURCE_NAME;
        option.name = FlowDatabase.DATA_SOURCE_NAME;
        option.title = "flow 数据源";
        option.jooqSchema = Public.PUBLIC;
        option.type = DataSourceType.orm;
        option.dbType = DbType.postgresql;
        return option;
    }

    /**
     * 创建原生查询 Builder，通过 getContext 适配借用事务。
     * @param session 可空业务会话，不保存到引擎成员
     * @param key 原生声明的数据源键
     * @param collection 集合名，来自生成表元数据
     * @return 当前调用的独立 Builder，不选择宿主连接
     */
    @Override
    public DataQuery<Session<User>, User, ?> buildQuery(Session<User> session, String key, String collection) {
        return new FlowQuery(this, session, key, collection);
    }

    /**
     * 使用生成元数据创建 Flow 查询。
     * @param session 可空业务会话
     * @param table 非空生成表，提供集合名
     * @return 路由到 postgresql.flow 的独立 Builder
     */
    @Override
    public DataQuery<Session<User>, User, ?> buildQuery(Session<User> session, TableImpl<?> table) {
        return buildQuery(session, DataSourceKey.POSTGRESQL, table.getName());
    }

    /**
     * 创建原生写入 Builder，通过 getContext 借用事务。
     * @param session 可空业务会话，不保存到引擎成员
     * @param key 原生声明的数据源键
     * @param collection 集合名，来自生成表元数据
     * @return 当前调用的独立写入 Builder
     */
    @Override
    public DataUpdater<Session<User>, User, ?> buildUpdater(Session<User> session, String key, String collection) {
        return new FlowUpdater(this, session, key, collection);
    }

    /**
     * 使用生成元数据创建 Flow 写入 Builder。
     * @param session 可空业务会话
     * @param table 非空生成表，提供集合名
     * @return 路由到 postgresql.flow 的独立 Builder
     */
    @Override
    public DataUpdater<Session<User>, User, ?> buildUpdater(Session<User> session, TableImpl<?> table) {
        return buildUpdater(session, DataSourceKey.POSTGRESQL, table.getName());
    }

    /**
     * 创建原生删除 Builder，通过 getContext 借用事务。
     * @param session 可空业务会话，不保存到引擎成员
     * @param key 原生声明的数据源键
     * @param collection 集合名，来自生成表元数据
     * @return 当前调用的独立删除 Builder
     */
    @Override
    public DataDeleter<Session<User>, User, ?> buildDeleter(Session<User> session, String key, String collection) {
        return new FlowDeleter(this, session, key, collection);
    }

    /**
     * 使用生成元数据创建 Flow 删除 Builder。
     * @param session 可空业务会话
     * @param table 非空生成表，提供集合名
     * @return 路由到 postgresql.flow 的独立 Builder
     */
    @Override
    public DataDeleter<Session<User>, User, ?> buildDeleter(Session<User> session, TableImpl<?> table) {
        return buildDeleter(session, DataSourceKey.POSTGRESQL, table.getName());
    }

    /**
     * 创建原生执行器，承接聚合 CTE、CAS 和行锁。
     * @param session 可空业务会话，不保存到引擎成员
     * @return 此次调用的独立执行器，显式 DSL 将绑定为借用会话
     */
    @Override
    public CommonExecutor<Session<User>, User> useExecutor(Session<User> session) {
        return new CommonExecutor<>(this, session) {
            /** @return 本次调用的独立 EngineContext，显式 DSL 不取得连接所有权 */
            @Override
            protected EngineContext<Session<User>, User> getContext() {
                return borrowedContext(session, dsls);
            }
        };
    }

    /**
     * 将显式提供的唯一上下文注册为外部会话；提交、回滚和关闭连接仍由调用方负责。
     * @param session 可空的业务会话，租户条件由 Repository 显式校验
     * @param dsls 此次调用的上下文集合，空集合允许 Engine 自建事务
     * @return 每次调用独立的 Engine 上下文，不保存请求状态
     * @throws IllegalArgumentException 一次调用提供多个数据库上下文时抛出
     */
    private EngineContext<Session<User>, User> borrowedContext(Session<User> session, Set<DSLContext> dsls) {
        if (dsls.size() > 1) throw new IllegalArgumentException("Flow CRUD requires one database context");
        EngineContext<Session<User>, User> context = new EngineContext<>(this);
        context.setContext(session);
        if (!dsls.isEmpty()) {
            DbDataSource source = dbDataSource(DataSourceKey.POSTGRESQL);
            DSLContext supplied = dsls.iterator().next();
            DSLContext borrowed = supplied.configuration().deriveSettings(settings ->
                settings.withRenderCatalog(false).withRenderSchema(false)).dsl();
            context.transactionManager.sessionMap.put(source.name,
                new JooqDbSessionImpl(this, borrowed));
        }
        return context;
    }

    /** 原生查询的具体 SELF 类型，适配借用上下文和数据库行映射。 */
    private static class FlowQuery extends DataQuery<Session<User>, User, FlowQuery> {
        private static final String ROW_COUNT = "flow_row_count";

        /**
         * 创建原生查询，不执行 SQL。
         * @param engine 提供元数据与上下文适配的 Flow Engine
         * @param session 可空业务会话
         * @param key 声明的数据源键
         * @param collection 集合名称
         */
        private FlowQuery(FlowDataSourceEngine engine, Session<User> session, String key, String collection) {
            super(engine, session, key, collection);
        }

        /** @return 本次调用独立的 EngineContext，借用显式 DSL */
        @Override
        protected EngineContext<Session<User>, User> getContext() {
            return ((FlowDataSourceEngine) configuration).borrowedContext(context, dsls);
        }

        /**
         * 通过原生数据库 Handler 直接映射行，保留 JOOQ JSONB 类型与列名映射。
         * @param <R> 调用方的数据库行类型
         * @param entryClass 非空行类型，不使用会丢失数据库类型的 JSON POJO 中转
         * @return 原生查询结果，筛选条件及分页直接传递
         */
        @Override
        public <R> ListResult<R> list(Class<R> entryClass) {
            isAsync(true);
            FindRequest request = FindRequest.Of(this);
            return executeResult(engineContext -> {
                if (!(getCollection() instanceof DbCollection<?> collection)) {
                    throw new IllegalStateException("Flow query requires a database collection");
                }
                DatabaseHandler<?> handler = collection.metaModel.handler;
                while (handler instanceof QueryHandlerProxyTemplate<?, ?> proxy) handler = proxy.handler;
                if (!(handler instanceof JooqDatabaseHandler<?> database)) {
                    throw new IllegalStateException("Flow query requires the native JOOQ database handler");
                }
                return ListResult.Of(database.query(engineContext, request, entryClass));
            });
        }

        /**
         * 对筛选后的数据库行计数，复合主键使用原生聚合，避免只对第一列去重。
         * @return 符合当前筛选条件的行数，不受查询分页影响
         */
        @Override
        public Long count() {
            if (!(getCollection() instanceof DbCollection<?> collection)
                    || collection.metaModel.getPrimaryKey().getFields().size() < 2) {
                return super.count();
            }
            var primaryField = collection.metaModel.getPrimaryKey().getFields().getFirst();
            var options = AggregateOptions.builder().filter(getFilter()).useEs(false)
                .aggregates(List.of(AggregateOptions.AggregateItem.Of(
                    AggregateType.COUNT, primaryField.getName(), ROW_COUNT))).build();
            return executeResult(engineContext -> collection.metaModel.handler
                .aggregate(engineContext, options).getFirst().getLong(ROW_COUNT));
        }
    }

    /** 原生更新的具体 SELF 类型，适配借用上下文与复合主键查询更新。 */
    private static class FlowUpdater extends DataUpdater<Session<User>, User, FlowUpdater> {
        /**
         * 创建原生更新，不执行 SQL。
         * @param engine 提供元数据与上下文适配的 Flow Engine
         * @param session 可空业务会话
         * @param key 声明的数据源键
         * @param collection 集合名称
         */
        private FlowUpdater(FlowDataSourceEngine engine, Session<User> session, String key, String collection) {
            super(engine, session, key, collection);
        }

        /** @return 本次调用独立的 EngineContext，借用显式 DSL */
        @Override
        protected EngineContext<Session<User>, User> getContext() {
            return ((FlowDataSourceEngine) configuration).borrowedContext(context, dsls);
        }

        /**
         * 复合主键按原始条件选取 Model，由原生 Handler 执行完整主键更新。
         * @param values 待更新字段，不修改调用方 Map；NULL 操作由原生 request 归一化
         */
        @Override
        public void update(Map<String, Object> values) {
            if (!(getCollection() instanceof DbCollection<?> collection)
                    || collection.metaModel.getPrimaryKey().getFields().size() < 2
                    || getFilter() == null || getFilter().isEmpty()) {
                super.update(values);
                return;
            }
            getValues().putAll(values);
            UpdateRequest request = UpdateRequest.FromDataUpdater(this);
            request.normalizeOperators();
            executeResult(engineContext -> {
                updateComposite(engineContext, collection, request);
                return null;
            });
        }

        /**
         * 复用原生 Model 校验、字段操作和数据库事件，避开默认 Repository 的单列主键路径。
         * @param <T> 当前集合的原生 Model 类型
         * @param engineContext 当前调用上下文及借用事务
         * @param collection 具有完整生成主键的集合
         * @param request 已归一化的字段和操作符，保留原查询条件
         */
        private <T extends Model> void updateComposite(EngineContext<Session<User>, User> engineContext,
                DbCollection<T> collection, UpdateRequest request) {
            var models = collection.metaModel.handler.find(engineContext,
                FindRequest.builder().filter(request.filter).cache(false).withParent(false).build());
            Map<String, Object> changes = new LinkedHashMap<>(request.values);
            changes.keySet().removeIf(name -> request.getBlack().contains(name)
                || (!request.getWhite().isEmpty() && !request.getWhite().contains(name)));
            var operators = new LinkedHashMap<>(request.operators);
            operators.keySet().removeIf(name -> request.getBlack().contains(name)
                || (!request.getWhite().isEmpty() && !request.getWhite().contains(name)));
            collection.metaModel.getPrimaryKey().getFields().forEach(field -> {
                changes.remove(field.getName());
                operators.remove(field.getName());
            });
            models.forEach(model -> {
                model.putAll(changes);
                model.putOperator(operators);
                model.updated(true);
                collection.metaModel.handler.update(engineContext, model, changes);
            });
        }
    }

    /** 原生删除的具体 SELF 类型，仅适配借用上下文。 */
    private static class FlowDeleter extends DataDeleter<Session<User>, User, FlowDeleter> {
        /**
         * 创建原生删除，不执行 SQL。
         * @param engine 提供元数据与上下文适配的 Flow Engine
         * @param session 可空业务会话
         * @param key 声明的数据源键
         * @param collection 集合名称
         */
        private FlowDeleter(FlowDataSourceEngine engine, Session<User> session, String key, String collection) {
            super(engine, session, key, collection);
        }

        /** @return 本次调用独立的 EngineContext，借用显式 DSL */
        @Override
        protected EngineContext<Session<User>, User> getContext() {
            return ((FlowDataSourceEngine) configuration).borrowedContext(context, dsls);
        }
    }

    /**
     * 使用 Micronaut 提供的基础依赖，再固定 Flow 数据源和追踪选项。
     * @param configuration 本次初始化的非空引擎配置，按父类约定修改
     */
    @Override
    public void configuration(EngineConfiguration configuration) {
        super.configuration(configuration);
        configureFlow(configuration);
    }

    /**
     * 固定 Flow 默认与管理数据源，不改动连接值或生成业务审计事实。
     * @param configuration 非空引擎配置，被设置为非 debug、基本追踪开启及详细追踪关闭
     */
    void configureFlow(EngineConfiguration configuration) {
        configuration.isDebug = false;
        configuration.openTrace(true);
        configuration.setOpenDetailTrace(false);
        configuration.setDefaultDBDataSource(DataSourceKey.POSTGRESQL);
        configuration.setManagerDataSourceKey(DataSourceKey.POSTGRESQL);
    }
}
