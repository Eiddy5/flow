package org.cses.flow.infrastructure.repositories;

import org.cses.flow.common.Fields;
import org.cses.flow.infrastructure.datapilot.DataSourceKey;
import org.cses.flow.infrastructure.datapilot.FlowDataSourceEngine;
import org.dataPilot.handler.DataQuery;
import org.dataPilot.db.search.filter.QueryCondition;
import org.dataPilot.repository.request.operator.SetNullOperator;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.InsertSetMoreStep;
import org.jooq.JSON;
import org.jooq.JSONB;
import org.jooq.Table;
import org.jooq.UpdateSetMoreStep;
import org.jooq.exception.InvalidResultException;
import org.paas.session.Session;
import org.paas.session.User;
import org.paas.json.JsonFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** 通过 Flow DataSourceEngine 复用数据库 CRUD 和原子 SQL 扩展；调用方拥有事务。 */
public abstract class BaseRepository {
    /** 表元数据的构造参数名称，仅本类使用。 */
    private static final String TABLE = "table";
    private static final String DATA_SOURCE_ENGINE = "dataSourceEngine";
    private static final String PRIMARY_KEY = "primaryKey";
    private FlowDataSourceEngine dataSourceEngine;
    private Table<?> table;

    /**
     * 绑定数据库引擎与表元数据，不取得连接或保存当前请求状态。
     * @param dataSourceEngine 非空 Flow Engine，业务实现共享同一实例但不共享调用状态
     * @param table 非空表元数据
     * @throws NullPointerException 任一元数据为空时抛出
     */
    protected BaseRepository(FlowDataSourceEngine dataSourceEngine, Table<?> table) {
        this.dataSourceEngine = Objects.requireNonNull(dataSourceEngine, DATA_SOURCE_ENGINE);
        this.table = Objects.requireNonNull(table, TABLE);
    }

    /**
     * 为当前表创建原生查询 Builder，关闭 ES 与关联装配。
     * @param dsl 非空调用方数据库上下文
     * @param condition 调用方提供的完整筛选条件
     * @return 此次调用独立的未执行 Builder
     */
    protected DataQuery<Session<User>, User, ?> query(DSLContext dsl, Condition condition) {
        return query(dsl, table, condition);
    }

    /**
     * 为指定表创建原生查询，条件使用生成元数据。
     * @param dsl 非空调用方数据库上下文
     * @param target 非空生成表元数据
     * @param condition 调用方提供的完整筛选条件
     * @return 此次调用独立的未执行 Builder，不持有连接所有权
     * @throws NullPointerException 上下文为空时抛出
     */
    protected DataQuery<Session<User>, User, ?> query(DSLContext dsl, Table<?> target, Condition condition) {
        return dataSourceEngine.buildQuery(Session.Robot, DataSourceKey.POSTGRESQL, target.getName())
            .dsl(Objects.requireNonNull(dsl, Fields.DSL))
            .useEs(false).withParent(false).cache(false)
            .filter(QueryCondition.empty().andJooq(condition));
    }

    /**
     * 保留运行时生成条件及查询上限，读取至多一行；不经过会丢失 transient 条件的 GetRequest JSON 转换。
     * @param <T> 完整表行的 Entry 类型
     * @param query 原生查询 Builder，最新版本查询必须显式设定 pageSize(1)
     * @param entryClass 完整表行的 Entry 类型
     * @return Entry；未命中时为 null
     * @throws InvalidResultException 唯一查询命中多行时抛出
     */
    protected <T> T readOne(DataQuery<Session<User>, User, ?> query, Class<T> entryClass) {
        var entries = engineCall(() -> query.list(entryClass)).items;
        if (entries.size() > 1) throw new InvalidResultException("Expected at most one database row");
        return entries.isEmpty() ? null : entries.getFirst();
    }

    /**
     * 使用原生创建保存 Entry 字段，不修改输入 Map。
     * @param dsl 非空调用方数据库上下文
     * @param fields 非空待创建字段，默认值按 Engine 创建协议处理
     */
    protected void create(DSLContext dsl, Map<String, Object> fields) {
        engineCall(() -> dataSourceEngine.buildUpdater(Session.Robot, DataSourceKey.POSTGRESQL, table.getName())
            .dsl(Objects.requireNonNull(dsl, Fields.DSL)).batchCreate(List.of(nativeFields(fields))));
    }

    /**
     * 使用原生冲突策略创建新行，完整复合主键来自生成元数据。
     * @param dsl 非空调用方数据库上下文
     * @param fields 非空待创建字段；主键冲突时不覆盖旧行，不修改输入
     */
    protected void createIfAbsent(DSLContext dsl, Map<String, Object> fields) {
        engineCall(() -> dataSourceEngine.buildUpdater(Session.Robot, DataSourceKey.POSTGRESQL, table.getName())
            .dsl(Objects.requireNonNull(dsl, Fields.DSL)).onConflict().doNothingOn(Objects.requireNonNull(table.getPrimaryKey()).getFields().stream()
                .map(Field::getName).toArray(String[]::new)).batchCreate(List.of(nativeFields(fields))));
    }

    /**
     * 按原生查询结果的完整主键更新字段；需要单语句竞争检查时使用 execute。
     * @param dsl 非空调用方数据库上下文
     * @param target 非空目标表元数据
     * @param condition 调用方提供的完整筛选条件
     * @param fields 非空字段 Map；null 显式清空列，主键从查询结果保留，输入不被修改
     */
    protected void updateFields(DSLContext dsl, Table<?> target, Condition condition, Map<String, Object> fields) {
        var primaryKey = Objects.requireNonNull(target.getPrimaryKey(), PRIMARY_KEY);
        engineCall(() -> {
            var updater = dataSourceEngine.buildUpdater(Session.Robot, DataSourceKey.POSTGRESQL, target.getName())
                .dsl(Objects.requireNonNull(dsl, Fields.DSL))
                .filter(QueryCondition.empty().andJooq(condition));
            Map<String, Object> values = new LinkedHashMap<>();
            fields.forEach((name, value) -> {
                if (primaryKey.getFields().stream().anyMatch(field -> field.getName().equals(name))) return;
                if (value == null) updater.set(name, SetNullOperator.Of());
                else values.put(name, nativeValue(value));
            });
            updater.update(values);
            return null;
        });
    }

    /**
     * 使用原生删除能力执行调用方提供的明确条件。
     * @param dsl 非空调用方数据库上下文
     * @param condition 调用方提供的完整筛选条件
     */
    protected void deleteRows(DSLContext dsl, Condition condition) {
        deleteRows(dsl, table, condition);
    }

    /**
     * 使用原生删除能力清理明确指定的目标表和完整条件。
     * @param dsl 调用方拥有的非空数据库上下文
     * @param target 非空生成表元数据
     * @param condition 调用方提供的明确完整筛选条件
     * @throws NullPointerException target 或 dsl 为 null 时抛出
     */
    protected void deleteRows(DSLContext dsl, Table<?> target, Condition condition) {
        engineCall(() -> {
            dataSourceEngine.buildDeleter(Session.Robot, DataSourceKey.POSTGRESQL, target.getName())
                .dsl(Objects.requireNonNull(dsl, Fields.DSL))
                .filter(QueryCondition.empty().andJooq(condition)).confirmDelete(true).delete();
            return null;
        });
    }

    /**
     * 通过原生执行器承接 Builder 无法表达的单语句 CTE、CAS 或事务行锁。
     * @param <T> 专用操作的结果类型
     * @param dsl 调用方拥有的唯一数据库上下文
     * @param operation 使用 Engine 选定的 Flow 会话执行操作，不自行取得连接
     * @return 操作结果；数据库异常保留其原类型，领域可按既有契约处理
     */
    protected <T> T execute(DSLContext dsl, Function<DSLContext, T> operation) {
        return engineCall(() -> dataSourceEngine.useExecutor(Session.Robot)
            .dsl(Objects.requireNonNull(dsl, Fields.DSL)).executeResult(context ->
            operation.apply(context.dsl(context.dbDataSource(DataSourceKey.POSTGRESQL)))));
    }

    /**
     * 还原原生执行器的异常包装，保留 CAS 与数据库故障的原异常类型。
     * @param <T> 原操作的结果类型
     * @param operation 当前调用操作，不保存为成员
     * @return 原操作结果，不复制或修改结果对象
     * @throws RuntimeException 操作失败时传播原运行时异常
     */
    private <T> T engineCall(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RuntimeException exception) {
            RuntimeException cause = exception;
            while (cause.getClass() == RuntimeException.class && cause.getCause() instanceof RuntimeException nested) {
                cause = nested;
            }
            throw cause;
        }
    }

    /**
     * 将数据库 JSON 包装值转换为 Engine 原生 JSON 树，不修改输入字段。
     * @param fields 非空数据库字段 Map
     * @return 只进行数据库类型适配的独立 Map
     */
    private Map<String, Object> nativeFields(Map<String, Object> fields) {
        Map<String, Object> values = new LinkedHashMap<>();
        fields.forEach((name, value) -> values.put(name, nativeValue(value)));
        return values;
    }

    /**
     * 保留 JSON 根节点形状，避免把 JOOQ 包装类当作普通 POJO 存储。
     * @param value 数据库字段值，可为空
     * @return Engine 可处理的原生值或 JSON 树
     */
    private Object nativeValue(Object value) {
        if (value instanceof JSONB jsonb) return JsonFactory.Parse(jsonb.data());
        if (value instanceof JSON json) return JsonFactory.Parse(json.data());
        return value;
    }

    /**
     * 构造未执行插入，保留显式 NULL，供调用方追加返回字段或 CTE。
     * @param dsl 调用方拥有的非空数据库上下文
     * @param fields 非空待插入字段 Map，不修改输入
     * @return 尚未执行的插入语句，使用调用方上下文
     * @throws NullPointerException 上下文或字段 Map 为空时抛出
     */
    protected InsertSetMoreStep<?> insertStatement(DSLContext dsl, Map<String, Object> fields) {
        return Objects.requireNonNull(dsl, Fields.DSL).insertInto(table).set(new LinkedHashMap<>(fields));
    }

    /**
     * 构造未执行更新，保留显式 NULL 并按表元数据移除主键；调用方必须追加 WHERE。
     * @param dsl 调用方拥有的非空数据库上下文
     * @param fields 非空待更新字段 Map，不修改输入
     * @return 尚未执行且不包含主键更新的语句
     * @throws NullPointerException 上下文或字段 Map 为空时抛出
     */
    protected UpdateSetMoreStep<?> updateStatement(DSLContext dsl, Map<String, Object> fields) {
        Map<String, Object> values = new LinkedHashMap<>(fields);
        if (table.getPrimaryKey() != null) {
            table.getPrimaryKey().getFields().forEach(field -> values.remove(field.getName()));
        }
        return Objects.requireNonNull(dsl, Fields.DSL).update(table).set(values);
    }
}
