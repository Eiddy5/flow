package org.cses.flow.common;

/** 跨类复用的字段与参数名称；数据库列名仍由 JOOQ 元数据提供。 */
public class Fields {
    /** 通用身份字段及参数名。 */
    public static final String ID = "id";
    /** 租户字段及参数名，使用 Java/JSON 的 camelCase 名称。 */
    public static final String COMPANY_ID = "companyId";
    /** 调用方数据库上下文参数名。 */
    public static final String DSL = "dsl";

    /** 禁止创建仅保存公共名称的对象。 */
    private Fields() {
    }
}
