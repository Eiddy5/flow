package org.cses.flow.common;

/** 跨类复用的字段与参数名称；数据库列名仍由 JOOQ 元数据提供。 */
public class Fields {
    /** 通用身份字段及参数名。 */
    public static final String ID = "id";
    /** 租户字段及参数名，使用 Java/JSON 的 camelCase 名称。 */
    public static final String COMPANY_ID = "companyId";
    /** 运行身份字段及参数名称。 */
    public static final String EXECUTION_ID = "executionId";
    /** 节点运行身份字段及参数名称。 */
    public static final String TASK_RUN_ID = "taskRunId";
    /** 运行变量树中的当前节点字段名称。 */
    public static final String TASK_RUN = "taskRun";
    /** 原业务尝试编号字段名称。 */
    public static final String ATTEMPT_NUMBER = "attemptNumber";
    /** 调用方数据库上下文参数名。 */
    public static final String DSL = "dsl";

    /** 禁止创建仅保存公共名称的对象。 */
    private Fields() {
    }
}
