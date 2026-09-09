package org.cses.flow.infrastructure.jooq;

import org.cses.flow.infrastructure.repositories.CasSupport;
import org.jooq.DSLContext;

import java.util.function.Function;

/**
 * Stable integration contract for the database owned by the Flow runtime.
 */
public class FlowDatabase {

    public static final String DATA_SOURCE_NAME = "flow";

    private FlowDatabase() {
    }

    /**
     * 执行一次数据库操作并自动释放仓储元数据，不创建覆盖业务调用的事务。
     * @param <T> 操作结果类型
     * @param dsl 入口已有的数据库上下文，原配置不变
     * @param operation 使用传入 DSL 完成加载和保存；显式事务须在返回前结束
     * @return 操作结果，返回的领域对象不携带跨操作写入凭据
     */
    public static <T> T execute(DSLContext dsl, Function<DSLContext, T> operation) {
        try (CasSupport context = new CasSupport(dsl)) {
            return operation.apply(context.dsl());
        }
    }
}
